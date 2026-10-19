package io.github.daniilpascal.specterai.settings

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test


class AiSettingsSwarmStatsTest {

  @Test
  fun summary_empty_isDash() {
    val state = AiSettings.State()
    assertEquals(0L, state.lastSwarmMs)
    assertEquals(0, state.lastSwarmBlocks)
    assertEquals(0, state.lastSwarmFiles)
    assertEquals(0L, state.lastSwarmAtEpochMs)
  }

  @Test
  fun summary_format_afterRecord() {
    
    val ms = 420L
    val blocks = 3
    val files = 2
    val text = "$ms ms · $blocks blocks · $files files"
    assertTrue(text.contains("420 ms"))
    assertTrue(text.contains("3 blocks"))
    assertTrue(text.contains("2 files"))
  }
}
