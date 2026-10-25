package com.ShowSeen.lightshadowart.mobile.net

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Parsing contract of `GET/POST /frame` (PRD 6.9).
 *
 * The editor builds its rows, the preview and the "restore default" state entirely from this reply, so
 * a shape mistake (a value parsed as Double where a colour is expected, `source` read from the wrong
 * key) would silently blank the page. Runs on the JVM: no device needed.
 */
class FrameReplyParsingTest {

    private val reply = JSONObject(
        """
        {
          "code": 200,
          "scope": "image",
          "source": "image",
          "path": "/data/media/0/Pictures/ALL/a.jpg",
          "style_id": "mat",
          "frame_enabled": true,
          "values": {
            "band_dp": 60.0,
            "margin_dp": 24.0,
            "band_color": -593385,
            "outer_line_dp": 1.5,
            "bottom_extra_dp": 20.0
          },
          "background": { "mode": 2, "color": -1, "has_image": true },
          "styles": [
            {
              "id": "mat", "name": "卡纸留白",
              "params": [
                { "id": "band_dp", "kind": "length", "min": 0, "max": 120, "default": 24.0, "advanced": false },
                { "id": "band_color", "kind": "color", "min": 0, "max": 0, "default": -593385, "advanced": false },
                { "id": "bottom_extra_dp", "kind": "length", "min": 0, "max": 60, "default": 0.0, "advanced": true }
              ]
            },
            { "id": "shadow", "name": "立体阴影", "params": [] }
          ],
          "panel": { "width": 1920, "height": 1280, "density": 2.0 }
        }
        """.trimIndent()
    )

    @Test
    fun stateCarriesScopeStyleAndSwitch() {
        val state = reply.toFrameConfig().state
        assertEquals("mat", state.styleId)
        assertEquals("单图覆盖时 source=image", true, state.fromImage)
        assertTrue(state.frameEnabled)
    }

    @Test
    fun valuesKeepTheirKind() {
        val values = reply.toFrameConfig().state.values
        assertEquals("长度按 Float 解析", 60f, values["band_dp"] as Float, 0.001f)
        assertEquals("颜色按 Int 解析", -593385, values["band_color"] as Int)
        assertEquals(1.5f, values["outer_line_dp"] as Float, 0.001f)
        assertEquals(20f, values["bottom_extra_dp"] as Float, 0.001f)
    }

    @Test
    fun backgroundAndPanelAreRead() {
        val config = reply.toFrameConfig()
        assertEquals(2, config.state.background.mode)
        assertEquals(-1, config.state.background.color)
        assertTrue("设备端存有底图", config.state.background.hasImage)
        assertEquals(1920, config.panel.widthPx)
        assertEquals(1280, config.panel.heightPx)
        assertEquals("预览按真实宽高比", 1280f / 1920f, config.panel.aspect, 0.0001f)
    }

    @Test
    fun stylesCarryTheirParameterSchema() {
        val config = reply.toFrameConfig()
        assertEquals(listOf("mat", "shadow"), config.styles.map { it.id })

        val mat = config.styles.first { it.id == "mat" }
        assertEquals(3, mat.params.size)
        val band = mat.params.first { it.id == "band_dp" }
        assertTrue(band.isLength)
        assertEquals(120f, band.maxDp, 0.001f)
        assertEquals(24f, band.defaultDp, 0.001f)
        assertTrue("基础参数不在高级组", !band.advanced)

        val colour = mat.params.first { it.id == "band_color" }
        assertTrue("颜色参数", !colour.isLength)
        assertEquals(-593385, colour.defaultColor)

        val extra = mat.params.first { it.id == "bottom_extra_dp" }
        assertTrue("下边加宽属于高级组", extra.advanced)
        assertEquals(0f, extra.defaultValue as Float, 0.001f)
    }

    @Test
    fun stateStyleFallsBackToTheFirstOfferedStyle() {
        val config = reply.toFrameConfig()
        assertEquals("mat", config.style()?.id)

        val unknown = JSONObject("""{"style_id":"gone","styles":[{"id":"mat","name":"m","params":[]}]}""")
            .toFrameConfig()
        assertEquals("未知样式回落第一个", "mat", unknown.style()?.id)
    }

    @Test
    fun aReplyWithoutPathIsTheDefaultLayer() {
        val defaultReply = JSONObject(
            """{"style_id":"mat","source":"default","values":{},"frame_enabled":false,
                "background":{"mode":0,"color":-16777216,"has_image":false},
                "styles":[{"id":"mat","name":"m","params":[]}],
                "panel":{"width":1920,"height":1280,"density":2.0}}""".trimIndent()
        ).toFrameConfig()
        assertTrue("默认层不是单图覆盖", !defaultReply.state.fromImage)
        assertTrue(!defaultReply.state.frameEnabled)
        assertEquals(0, defaultReply.state.background.mode)
        assertEquals(0, defaultReply.state.values.size)
    }

    @Test
    fun emptyReplyDoesNotCrash() {
        val config = (null as JSONObject?).toFrameConfig()
        assertNotNull(config)
        assertEquals("", config.state.styleId)
        assertTrue(config.styles.isEmpty())
        assertEquals("空回执也要给出面板默认值，预览才不会崩", 1920, config.panel.widthPx)
        assertNull(config.style())
    }
}
