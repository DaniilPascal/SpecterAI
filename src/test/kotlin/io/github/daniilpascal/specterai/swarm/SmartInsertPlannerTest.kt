package io.github.daniilpascal.specterai.swarm

import io.github.daniilpascal.specterai.ai.AiCodeTemplates
import io.github.daniilpascal.specterai.ai.TargetLanguage
import io.github.daniilpascal.specterai.swarm.SmartInsertPlanner
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test


class SmartInsertPlannerTest {

  @Test
  fun plan_importsNearClass() {
    val doc = """
      package demo
      
      import java.util.List
      
      class Demo {
          fun x() {}
      }
    """.trimIndent()

    val blocks = listOf(
      AiCodeTemplates.SemanticBlock(
        code = "import kotlin.io.path.Path",
        slot = SmartInsertPlanner.Slot.IMPORTS,
        label = "imports",
      ),
      AiCodeTemplates.SemanticBlock(
        code = "fun y() {}",
        slot = SmartInsertPlanner.Slot.CLASS_END,
        label = "member",
      ),
    )
    val placements = SmartInsertPlanner.plan(doc, commandOffset = doc.indexOf("fun x"), blocks, TargetLanguage.KOTLIN)
    assertEquals(2, placements.size)
    assertEquals(SmartInsertPlanner.Slot.IMPORTS, placements[0].slot)
    assertTrue(placements[0].offset > doc.indexOf("import java"))
    assertEquals(SmartInsertPlanner.Slot.CLASS_END, placements[1].slot)
  }

  @Test
  fun parseSlotTag_aliases() {
    assertEquals(SmartInsertPlanner.Slot.IMPORTS, SmartInsertPlanner.parseSlotTag("imports"))
    assertEquals(SmartInsertPlanner.Slot.NEAR_CARET, SmartInsertPlanner.parseSlotTag("near"))
    assertEquals(SmartInsertPlanner.Slot.CLASS_END, SmartInsertPlanner.parseSlotTag("class"))
    assertEquals(SmartInsertPlanner.Slot.FILE_END, SmartInsertPlanner.parseSlotTag("file"))
  }
}
