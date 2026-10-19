package io.github.daniilpascal.specterai

import io.github.daniilpascal.specterai.ai.AiClient
import io.github.daniilpascal.specterai.ai.AiCodeTemplates
import io.github.daniilpascal.specterai.ai.TargetLanguage
import io.github.daniilpascal.specterai.optim.FunctionOptimScanner
import io.github.daniilpascal.specterai.optim.OptimLang
import io.github.daniilpascal.specterai.swarm.MultiFilePlanner
import io.github.daniilpascal.specterai.swarm.SmartInsertPlanner
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test


class IdeSmokeTest {

  @Test
  fun nestedClass_classSlot_targetsInnermostBody() {
    val doc = """
      class Outer {
          fun a() {}
          class Inner {
              fun b() {}
              // caret here
          }
      }
    """.trimIndent()
    val caret = doc.indexOf("// caret here")
    assertTrue(caret > 0)

    val blocks = listOf(
      AiCodeTemplates.SemanticBlock(
        code = "fun nestedMember() = 1",
        slot = SmartInsertPlanner.Slot.CLASS_END,
        label = "member",
      ),
    )
    val placements = SmartInsertPlanner.plan(doc, caret, blocks, TargetLanguage.KOTLIN)
    assertEquals(1, placements.size)
    val at = placements[0].offset

    
    assertTrue(at > caret, "insert ($at) should be after caret ($caret)")
    assertTrue(doc.substring(0, at).contains("class Inner"))
    assertTrue(doc.substring(at).trimStart().startsWith("}"))
    
    val remainingCloses = doc.substring(at).count { it == '}' }
    assertTrue(remainingCloses >= 2, "expected Inner+Outer closes after insert, got $remainingCloses")

    
    val outerCaret = doc.indexOf("fun a()")
    val outerAt = SmartInsertPlanner.findClassBodyEndOffset(doc, outerCaret)!!
    assertTrue(outerAt > at, "Outer insert ($outerAt) should be after Inner insert ($at)")
  }

  @Test
  fun optim_expressionBody_scored() {
    val src = """
      fun add(a: Int, b: Int) = a + b
      fun empty() {}
    """.trimIndent()
    val hits = FunctionOptimScanner.scan(src, OptimLang.JVM)
    val add = hits.find { it.name == "add" }
    assertNotNull(add)
    assertNotNull(add!!.optim)
    assertTrue(add.optim!!.percent in 1..99)
    val empty = hits.find { it.name == "empty" }
    if (empty != null) {
      assertEquals(null, empty.optim)
    }
  }

  @Test
  fun parse_filePathTag_multiFile() {
    val content = """
      ### near
      fun main() {}
      
      ### file:FooTest.kt
      fun testFoo() {}
    """.trimIndent()
    val blocks = AiClient.parseSemanticBlocks(content, limit = 4, language = TargetLanguage.KOTLIN)
    assertEquals(2, blocks.size)
    assertEquals("FooTest.kt", blocks[1].targetPath)
    assertTrue(blocks[1].isCrossFile)
    assertTrue(AiCodeTemplates.SwarmPlan("t", blocks).hasMultiFile)
  }

  @Test
  fun multiFileResolve_classifiesTargets() {
    val near = AiCodeTemplates.SemanticBlock(
      code = "val x = 1",
      slot = SmartInsertPlanner.Slot.NEAR_CARET,
      label = "near",
    )
    val other = AiCodeTemplates.SemanticBlock(
      code = "val y = 2",
      slot = SmartInsertPlanner.Slot.FILE_END,
      label = "other",
      targetPath = "Other.kt",
    )
    val unsafe = AiCodeTemplates.SemanticBlock(
      code = "bad",
      slot = SmartInsertPlanner.Slot.FILE_END,
      label = "bad",
      targetPath = "../secret.kt",
    )
    
    val plan = MultiFilePlanner.ResolvedPlan(
      currentFile = null,
      targets = listOf(
        MultiFilePlanner.Target(near, relativePath = null, virtualFile = null, needsCreate = false),
        MultiFilePlanner.Target(
          other.copy(targetPath = "Other.kt"),
          relativePath = "Other.kt",
          virtualFile = null,
          needsCreate = true,
        ),
        MultiFilePlanner.Target(
          unsafe.copy(targetPath = null),
          relativePath = null,
          virtualFile = null,
          needsCreate = false,
          rejectReason = "небезопасный путь: ../secret.kt",
        ),
      ),
    )
    assertTrue(plan.isMultiFile)
    assertEquals(1, plan.sameFileBlocks.size)
    assertEquals(1, plan.otherFileTargets.size)
    assertEquals(1, plan.rejected.size)
    assertEquals("Other.kt", plan.otherFileTargets[0].relativePath)
    assertTrue(plan.otherFileTargets[0].needsCreate)
    val summary = MultiFilePlanner.summary(plan)
    assertTrue(summary.contains("Other.kt"))
    assertTrue(summary.contains("skip") || summary.contains("небезопасн"))
  }

  @Test
  fun sanitize_and_parse_reject_absolute() {
    assertEquals("src/Foo.kt", MultiFilePlanner.sanitizeRelativePath("src/Foo.kt"))
    assertEquals(null, MultiFilePlanner.sanitizeRelativePath("/abs/Foo.kt"))
    val content = """
      ### file:../Escape.kt
      class Escape
    """.trimIndent()
    val blocks = AiClient.parseSemanticBlocks(content, 2, TargetLanguage.KOTLIN)
    
    assertTrue(blocks.isEmpty() || blocks.none { it.targetPath == "../Escape.kt" })
    assertFalse(blocks.any { it.isCrossFile && it.targetPath?.contains("..") == true })
  }
}
