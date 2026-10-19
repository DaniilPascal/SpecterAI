package io.github.daniilpascal.specterai.swarm

import io.github.daniilpascal.specterai.ai.AiCodeTemplates
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test


class DiffPreviewTest {

  @Test
  fun buildModel_optimFix_includesWasAndWillBe() {
    val plan = AiCodeTemplates.SwarmPlan(
      title = "fix",
      blocks = listOf(
        AiCodeTemplates.SemanticBlock(
          code = "fun foo() = 42",
          slot = SmartInsertPlanner.Slot.NEAR_CARET,
          label = "fix",
        ),
      ),
    )
    val multi = MultiFilePlanner.ResolvedPlan(
      currentFile = null,
      targets = listOf(
        MultiFilePlanner.Target(plan.blocks[0], null, null, needsCreate = false),
      ),
    )
    val model = DiffPreviewSession.buildModel(
      plan = plan,
      multi = multi,
      replaceRange = 10 to 40,
      oldSnippet = "fun foo() {\n  return 1\n}",
    )
    assertTrue(model.title.contains("Optim Fix"))
    assertTrue(model.previewText.contains("——— was ———"))
    assertTrue(model.previewText.contains("——— will be ———"))
    assertTrue(model.previewText.contains("fun foo() = 42"))
    val hl = model.highlightRange
    assertNotNull(hl)
    assertEquals(10, hl!!.first)
    assertEquals(39, hl.last)
  }

  @Test
  fun buildModel_multiFile_listsPaths() {
    val near = AiCodeTemplates.SemanticBlock(
      code = "val x = 1",
      slot = SmartInsertPlanner.Slot.NEAR_CARET,
    )
    val other = AiCodeTemplates.SemanticBlock(
      code = "val y = 2",
      slot = SmartInsertPlanner.Slot.FILE_END,
      targetPath = "Other.kt",
    )
    val plan = AiCodeTemplates.SwarmPlan("t", listOf(near, other))
    val multi = MultiFilePlanner.ResolvedPlan(
      currentFile = null,
      targets = listOf(
        MultiFilePlanner.Target(near, null, null, false),
        MultiFilePlanner.Target(other, "Other.kt", null, needsCreate = true),
      ),
    )
    val model = DiffPreviewSession.buildModel(plan, multi, replaceRange = null, oldSnippet = null)
    assertTrue(model.title.contains("multi-file"))
    assertTrue(model.summaryHtml.contains("Other.kt"))
    assertTrue(model.previewText.contains("### file:Other.kt"))
  }
}
