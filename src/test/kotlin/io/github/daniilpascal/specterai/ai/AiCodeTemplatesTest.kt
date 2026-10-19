package io.github.daniilpascal.specterai.ai

import io.github.daniilpascal.specterai.ai.AiCodeTemplates
import io.github.daniilpascal.specterai.ai.TargetLanguage
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test


class AiCodeTemplatesTest {

  @Test
  fun hello_java_isNotKotlin() {
    val plan = AiCodeTemplates.planFor("hello world", TargetLanguage.JAVA)
    val all = plan.blocks.joinToString("\n") { it.code }
    assertTrue(all.contains("System.out.println") || all.contains("public static"))
    assertFalse(all.contains("fun main"))
    assertFalse(all.contains("fun greet"))
  }

  @Test
  fun hello_python_isNotKotlin() {
    val plan = AiCodeTemplates.planFor("hello world", TargetLanguage.PYTHON)
    val all = plan.blocks.joinToString("\n") { it.code }
    assertTrue(all.contains("print(") || all.contains("def main"))
    assertFalse(all.contains("fun main"))
    assertFalse(all.contains("println("))
  }

  @Test
  fun login_go_usesStruct() {
    val plan = AiCodeTemplates.planFor("login form", TargetLanguage.GO)
    val all = plan.blocks.joinToString("\n") { it.code }
    assertTrue(all.contains("type LoginState") || all.contains("ValidateLogin"))
    assertFalse(all.contains("data class LoginState"))
  }

  @Test
  fun default_unknown_becomesTextNotKotlin() {
    val plan = AiCodeTemplates.planFor("summarize notes", TargetLanguage.UNKNOWN)
    val all = plan.blocks.joinToString("\n") { it.code }
    assertFalse(all.contains("fun do"), "UNKNOWN must not emit Kotlin doTask stubs: $all")
  }
}
