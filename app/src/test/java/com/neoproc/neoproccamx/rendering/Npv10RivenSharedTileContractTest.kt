package com.neoproc.neoproccamx.rendering

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class Npv10RivenSharedTileContractTest {
    private val shader: String
        get() = File("src/main/cpp/shaders/vulkan/neo_p010_export_enhance.comp").readText()

    @Test
    fun `riven search uses one bounded shared tile instead of per-candidate SSBO reads`() {
        val source = shader
        val search = source.substringAfter("vec2 rivenBlockMatchFlow").substringBefore("uint rivenP010LumaDeblur")

        assertTrue(source.contains("const int RIVEN_TILE_HALO = 6;"))
        assertTrue(source.contains("const int RIVEN_TILE_EDGE = RIVEN_WORKGROUP_EDGE + RIVEN_TILE_HALO * 2;"))
        assertTrue(source.contains("shared uint rivenCurrentLumaTile[RIVEN_TILE_SAMPLE_COUNT];"))
        assertTrue(source.contains("shared uint rivenPreviousLumaTile[RIVEN_TILE_SAMPLE_COUNT];"))
        assertTrue(source.contains("memoryBarrierShared();"))
        assertTrue(source.contains("barrier();"))
        assertTrue(search.contains("rivenCurrentLumaTile[currentTileIndex]"))
        assertTrue(search.contains("rivenPreviousLumaTile[previousTileIndex]"))
        assertFalse(search.contains("readCurrentP010Sample"))
        assertFalse(search.contains("readPreviousP010Sample"))
    }

    @Test
    fun `riven search stops at the exact global minimum without changing tie order`() {
        val search = shader
            .substringAfter("vec2 rivenBlockMatchFlow")
            .substringBefore("uint rivenP010LumaDeblur")

        assertTrue(search.contains("float bestSad = 1e20;"))
        assertTrue(search.contains("float sad = 0.0;"))
        assertTrue(search.contains("sad += abs(cur - prv);"))
        assertTrue(search.contains("if (sad < bestSad)"))
        assertTrue(search.contains("if (bestSad == 0.0) return bestFlow;"))
    }

    @Test
    fun `all invocations populate the shared tile before edge invocations may return`() {
        val main = shader.substringAfter("void main()")
        val load = main.indexOf("loadRivenLumaTiles();")
        val edgeReturn = main.indexOf("if (x >= pushData.width || y >= pushData.height) return;")

        assertTrue(load >= 0)
        assertTrue(edgeReturn >= 0)
        assertTrue(load < edgeReturn)
    }

    @Test
    fun `tile halo covers search patch and maximum deblur radius within portable shared memory`() {
        val workgroupEdge = 16
        val halo = 6
        val searchRadius = 3
        val patchRadius = 1
        val maximumDeblurRadius = 6
        val tileEdge = workgroupEdge + halo * 2
        val sharedBytes = tileEdge * tileEdge * UInt.SIZE_BYTES * 2

        assertTrue(halo >= searchRadius + patchRadius)
        assertTrue(halo >= maximumDeblurRadius)
        assertTrue(sharedBytes <= 16 * 1024)
    }
}
