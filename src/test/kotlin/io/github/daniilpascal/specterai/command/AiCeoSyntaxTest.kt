package io.github.daniilpascal.specterai.command

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AiCeoSyntaxTest {

  @Test
  fun complete_specter_ceo() {
    val src = """
      fun main() {}
      Specter.AI.CEO();
    """.trimIndent()
    val m = AiCeoSyntax.findCompleteNearCaret(src, src.length)
    assertNotNull(m)
    assertTrue(m!!.complete)
    assertTrue(src.substring(m.start, m.end).contains("CEO"))
  }

  @Test
  fun complete_short_s_ceo() {
    val src = "S.AI.CEO();"
    val m = AiCeoSyntax.findCompleteNearCaret(src, src.length)
    assertNotNull(m)
    assertEquals(src, src.substring(m!!.start, m.end))
  }

  @Test
  fun normal_ai_start_not_ceo() {
    val src = """Specter.AI("hello").start(14);"""
    assertNull(AiCeoSyntax.findCompleteNearCaret(src, src.length))
    val ai = AiCommandSyntax.findNearCaret(src, src.length)
    assertNotNull(ai)
    assertEquals("hello", ai!!.prompt)
  }

  @Test
  fun signature_text() {
    assertEquals(
      "CEO SpecterAI Daniil_Pascal (Даниил Машичев)",
      AiCeoSyntax.SIGNATURE,
    )
  }
}
