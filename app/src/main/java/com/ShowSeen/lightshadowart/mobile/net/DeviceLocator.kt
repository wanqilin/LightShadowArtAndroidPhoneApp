package com.ShowSeen.lightshadowart.mobile.net

import android.util.Log
import com.ShowSeen.lightshadowart.mobile.SessionStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import kotlin.coroutines.resume

/**
 * Finds the frame on the LAN (PRD 16.3).
 *
 * Two sources, in this order:
 * 1. mDNS (`NsdDiscoverer`), which is what a normally configured router answers;
 * 2. a subnet scan of the phone's own /24 for the device's HTTP port — routers that block multicast
 *    (AP isolation, "guest network" isolation) break mDNS but not unicast, and the frame is then
 *    still reachable.
 *
 * There is deliberately no manual IP entry: the frame is unattended, so nobody can read an address
 * off its screen, and the address is only useful when the phone is on the same subnet — exactly the
 * case the scan already covers.
 */
class DeviceLocator(private val nsd: NsdDiscoverer) {

    /**
     * @param deviceToken token recorded for the frame; mDNS results carrying a different token are
     *        ignored, because a rotated token means the pairing was reset and the phone must scan again
     */
    suspend fun findLanEndpoint(deviceToken: String, timeoutMs: Long): DeviceEndpoint? {
        val discovered = discoverViaNsd(timeoutMs)
        val match = discovered.firstOrNull { deviceToken.isEmpty() || it.deviceToken == deviceToken }
        if (match != null) {
            Log.i(TAG, "lan found via mDNS: ${match.host}:${match.port}")
            return match.toEndpoint()
        }

        val host = scanSubnet()
        if (host == null) {
            Log.i(TAG, "lan not found: no mDNS answer, no open port in the local /24")
            return null
        }
        Log.i(TAG, "lan found via subnet scan: $host")
        return DeviceEndpoint(
            host = host,
            port = DeviceEndpoint.DEFAULT_PORT,
            deviceToken = deviceToken,
            mode = SessionStore.lastDeviceMode
        )
    }

    private suspend fun discoverViaNsd(timeoutMs: Long): List<DiscoveredDevice> =
        suspendCancellableCoroutine { continuation ->
            nsd.discover(timeoutMs) { devices ->
                if (continuation.isActive) continuation.resume(devices)
            }
            continuation.invokeOnCancellation { nsd.cancel() }
        }

    /**
     * Probe the phone's own /24 for the device port. Home routers use /24 in practice; scanning the
     * full range of a /16 would mean 65k probes and is not worth it for one frame.
     */
    private suspend fun scanSubnet(): String? = withContext(Dispatchers.IO) {
        val local = localIpv4() ?: return@withContext null
        val base = ipv4ToLong(local) ?: return@withContext null
        val subnet = base and 0xFFFFFF00L
        val permits = Semaphore(SCAN_CONCURRENCY)
        coroutineScope {
            (1..254).map { host ->
                async {
                    val ip = longToIpv4(subnet or host.toLong())
                    if (ip == local) {
                        null
                    } else {
                        permits.withPermit { if (portOpen(ip)) ip else null }
                    }
                }
            }.awaitAll().firstOrNull { it != null }
        }
    }

    /** Single TCP connect on the device port: an open port means something is listening there */
    private fun portOpen(host: String): Boolean = runCatching {
        Socket().use { it.connect(InetSocketAddress(host, DeviceEndpoint.DEFAULT_PORT), PROBE_TIMEOUT_MS) }
    }.isSuccess

    /** IPv4 of this phone on a site-local interface, preferring wlan */
    private fun localIpv4(): String? {
        val candidates = runCatching {
            NetworkInterface.getNetworkInterfaces().toList()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { nic ->
                    nic.inetAddresses.toList()
                        .filterIsInstance<Inet4Address>()
                        .filter { it.isSiteLocalAddress }
                        .map { nic.name to it.hostAddress.orEmpty() }
                }
        }.getOrDefault(emptyList())
        return candidates.firstOrNull { it.first.startsWith("wlan") }?.second
            ?: candidates.firstOrNull()?.second
    }

    private fun ipv4ToLong(ip: String): Long? {
        val parts = ip.split('.')
        if (parts.size != 4) return null
        var value = 0L
        for (part in parts) {
            val octet = part.toIntOrNull() ?: return null
            if (octet !in 0..255) return null
            value = (value shl 8) or octet.toLong()
        }
        return value
    }

    private fun longToIpv4(value: Long): String = listOf(
        (value ushr 24) and 0xFF,
        (value ushr 16) and 0xFF,
        (value ushr 8) and 0xFF,
        value and 0xFF
    ).joinToString(".")

    private companion object {
        const val TAG = "DeviceLocator"

        /** Concurrent TCP probes while scanning the subnet */
        const val SCAN_CONCURRENCY = 32

        /** Per-host timeout; a device that is up answers in milliseconds */
        const val PROBE_TIMEOUT_MS = 150
    }
}
