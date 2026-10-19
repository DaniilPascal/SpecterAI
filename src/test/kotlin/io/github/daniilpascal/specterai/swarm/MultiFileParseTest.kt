package io.github.daniilpascal.specterai.swarm

import io.github.daniilpascal.specterai.ai.AiClient
import io.github.daniilpascal.specterai.ai.TargetLanguage
import io.github.daniilpascal.specterai.swarm.MultiFilePlanner
import io.github.daniilpascal.specterai.swarm.SmartInsertPlanner
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test


class MultiFileParseTest {

  @Test
  fun sanitize_acceptsRelativePaths() {
    assertEquals("FooTest.kt", MultiFilePlanner.sanitizeRelativePath("FooTest.kt"))
    assertEquals("FooTest.kt", MultiFilePlanner.sanitizeRelativePath("./FooTest.kt"))
    assertEquals(
      "src/test/kotlin/FooTest.kt",
      MultiFilePlanner.sanitizeRelativePath("src/test/kotlin/FooTest.kt"),
    )
    assertEquals("_init_.py", MultiFilePlanner.sanitizeRelativePath("_init_.py"))
  }

  @Test
  fun sanitize_rejectsUnsafePaths() {
    assertNull(MultiFilePlanner.sanitizeRelativePath("/etc/passwd"))
    assertNull(MultiFilePlanner.sanitizeRelativePath("../secret.kt"))
    assertNull(MultiFilePlanner.sanitizeRelativePath("C:/Windows/x.kt"))
    assertNull(MultiFilePlanner.sanitizeRelativePath("https://evil.com/x.kt"))
    assertNull(MultiFilePlanner.sanitizeRelativePath(""))
    assertNull(MultiFilePlanner.sanitizeRelativePath(".."))
  }

  @Test
  fun parse_filePathTag() {
    val content = """
      ### imports
      import foo.Bar
      
      ### near
      fun main() {}
      
      ### file:MainTest.kt
      fun testMain() {}
    """.trimIndent()

    val blocks = AiClient.parseSemanticBlocks(content, limit = 4, language = TargetLanguage.KOTLIN)
    assertEquals(3, blocks.size)
    assertNull(blocks[0].targetPath)
    assertEquals(SmartInsertPlanner.Slot.IMPORTS, blocks[0].slot)
    assertEquals(SmartInsertPlanner.Slot.NEAR_CARET, blocks[1].slot)
    assertEquals("MainTest.kt", blocks[2].targetPath)
    assertTrue(blocks[2].isCrossFile)
    assertEquals(SmartInsertPlanner.Slot.FILE_END, blocks[2].slot)
    assertTrue(blocks[2].code.contains("testMain"))
  }

  @Test
  fun parse_plainFileTag_staysSameFile() {
    val content = """
      ### file
      class Extra
    """.trimIndent()
    val blocks = AiClient.parseSemanticBlocks(content, limit = 2, language = TargetLanguage.KOTLIN)
    assertEquals(1, blocks.size)
    assertNull(blocks[0].targetPath)
    assertFalse(blocks[0].isCrossFile)
    assertEquals(SmartInsertPlanner.Slot.FILE_END, blocks[0].slot)
  }

  @Test
  fun swarmPlan_hasMultiFileFlag() {
    val content = """
      ### near
      val x = 1
      ### file:Other.kt
      val y = 2
    """.trimIndent()
    val blocks = AiClient.parseSemanticBlocks(content, 4, TargetLanguage.KOTLIN)
    val plan = io.github.daniilpascal.specterai.ai.AiCodeTemplates.SwarmPlan("t", blocks)
    assertTrue(plan.hasMultiFile)
    assertNotNull(blocks.find { it.targetPath == "Other.kt" })
  }
}
