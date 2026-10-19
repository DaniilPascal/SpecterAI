package io.github.daniilpascal.specterai.ai

import io.github.daniilpascal.specterai.swarm.SmartInsertPlanner

object AiCodeTemplates {

  data class SemanticBlock(
    val code: String,
    val slot: SmartInsertPlanner.Slot? = null,
    val label: String = slot?.name ?: "block",
    
    val targetPath: String? = null,
  ) {
    val isCrossFile: Boolean get() = !targetPath.isNullOrBlank()
  }

  data class SwarmPlan(
    val title: String,
    val blocks: List<SemanticBlock>,
  ) {
    val hasMultiFile: Boolean get() = blocks.any { it.isCrossFile }

    companion object {
      fun fromCode(title: String, codeBlocks: List<String>) = SwarmPlan(
        title = title,
        blocks = codeBlocks.map { SemanticBlock(code = it) },
      )
    }
  }

  
  fun planFor(prompt: String, language: TargetLanguage = TargetLanguage.UNKNOWN): SwarmPlan {
    val lang = if (language == TargetLanguage.UNKNOWN) TargetLanguage.TEXT else language
    val p = prompt.lowercase()
    return when {
      lang.isProse || lang == TargetLanguage.TEXT || lang == TargetLanguage.MARKDOWN ->
        prosePlan(prompt, lang)
      looksLikeHello(p) -> helloPlan(lang)
      listOf("вход", "login", "парол", "password", "кнопк", "button", "auth").any { it in p } ->
        loginPlan(prompt, lang)
      listOf("тест", "test").any { it in p } ->
        testPlan(prompt, lang)
      else ->
        defaultPlan(prompt, lang)
    }
  }

  private fun looksLikeHello(p: String): Boolean =
    listOf(
      "hello", "helloworld", "привет", "hi world",
      "выведи", "печат", "println", "print(", "консол", "console.log",
    ).any { it in p } ||
      (p.contains("world") && (p.contains("напиш") || p.contains("write") || p.contains("код")))

  private fun prosePlan(prompt: String, language: TargetLanguage): SwarmPlan {
    val title = prompt.replace("\n", " ").take(60).ifBlank { "note" }
    val body = when (language) {
      TargetLanguage.MARKDOWN -> """
        |## $title
        |
        |$prompt
        |
        |### Details
        |- Goal: describe the request above
        |- Status: draft
        |
        |### Next steps
        |1. Clarify requirements
        |2. Implement
        |3. Verify
      """.trimMargin()
      else -> """
        |$title
        |${"=".repeat(title.length.coerceAtMost(40))}
        |
        |Request: $prompt
        |
        |Summary
        |- This is a plain-text note generated as a local template.
        |- Replace with a fuller description as needed.
        |
        |Outline
        |1. Context
        |2. Steps
        |3. Result
      """.trimMargin()
    }
    return SwarmPlan(
      title = "text: ${language.id}",
      blocks = listOf(
        SemanticBlock(label = "text", slot = SmartInsertPlanner.Slot.NEAR_CARET, code = body),
      ),
    )
  }

  private fun helloPlan(language: TargetLanguage): SwarmPlan {
    val (main, helper) = when (language) {
      TargetLanguage.JAVA ->
        """
          |public static void main(String[] args) {
          |    System.out.println("Hello, World!");
          |}
        """.trimMargin() to """
          |public static String greet(String name) {
          |    if (name == null || name.isBlank()) name = "World";
          |    return "Hello, " + name + "!";
          |}
        """.trimMargin()

      TargetLanguage.PYTHON ->
        """
          |def main():
          |    print("Hello, World!")
          |
          |
          |if __name__ == "__main__":
          |    main()
        """.trimMargin() to """
          |def greet(name: str = "World") -> str:
          |    return f"Hello, {name}!"
        """.trimMargin()

      TargetLanguage.JAVASCRIPT, TargetLanguage.TYPESCRIPT ->
        """
          |function main() {
          |  console.log("Hello, World!");
          |}
          |
          |main();
        """.trimMargin() to """
          |function greet(name${if (language == TargetLanguage.TYPESCRIPT) ": string" else ""} = "World")${if (language == TargetLanguage.TYPESCRIPT) ": string" else ""} {
          |  return `Hello, ${"$"}{name}!`;
          |}
        """.trimMargin()

      TargetLanguage.GO ->
        """
          |func main() {
          |	fmt.Println("Hello, World!")
          |}
        """.trimMargin() to """
          |func greet(name string) string {
          |	if name == "" {
          |		name = "World"
          |	}
          |	return "Hello, " + name + "!"
          |}
        """.trimMargin()

      TargetLanguage.RUST ->
        """
          |fn main() {
          |    println!("Hello, World!");
          |}
        """.trimMargin() to """
          |fn greet(name: &str) -> String {
          |    format!("Hello, {}!", name)
          |}
        """.trimMargin()

      TargetLanguage.C ->
        """
          |#include <stdio.h>
          |
          |int main(void) {
          |    printf("Hello, World!\n");
          |    return 0;
          |}
        """.trimMargin() to """
          |void greet(const char *name) {
          |    printf("Hello, %s!\n", name);
          |}
        """.trimMargin()

      TargetLanguage.CPP ->
        """
          |#include <iostream>
          |
          |int main() {
          |    std::cout << "Hello, World!" << std::endl;
          |    return 0;
          |}
        """.trimMargin() to """
          |std::string greet(const std::string& name = "World") {
          |    return "Hello, " + name + "!";
          |}
        """.trimMargin()

      TargetLanguage.CSHARP ->
        """
          |static void Main(string[] args) {
          |    Console.WriteLine("Hello, World!");
          |}
        """.trimMargin() to """
          |static string Greet(string name = "World") {
          |    return $"Hello, {name}!";
          |}
        """.trimMargin()

      TargetLanguage.SHELL ->
        """
          |#!/usr/bin/env bash
          |echo "Hello, World!"
        """.trimMargin() to """
          |greet() {
          |  local name="${'$'}{1:-World}"
          |  echo "Hello, ${'$'}name!"
          |}
        """.trimMargin()

      TargetLanguage.SQL ->
        """
          |SELECT 'Hello, World!' AS message;
        """.trimMargin() to """
          |-- helper view
          |-- CREATE VIEW greet AS SELECT 'Hello' AS message;
        """.trimMargin()

      TargetLanguage.SWIFT ->
        """
          |func main() {
          |    print("Hello, World!")
          |}
        """.trimMargin() to """
          |func greet(_ name: String = "World") -> String {
          |    return "Hello, \(name)!"
          |}
        """.trimMargin()

      TargetLanguage.PHP ->
        """
          |<?php
          |echo "Hello, World!\n";
        """.trimMargin() to """
          |function greet(string ${'$'}name = "World"): string {
          |    return "Hello, ${'$'}name!";
          |}
        """.trimMargin()

      TargetLanguage.RUBY ->
        """
          |puts "Hello, World!"
        """.trimMargin() to """
          |def greet(name = "World")
          |  "Hello, #{name}!"
          |end
        """.trimMargin()

      TargetLanguage.HTML ->
        """
          |<!DOCTYPE html>
          |<html lang="en">
          |<head><meta charset="utf-8"><title>Hello</title></head>
          |<body><h1>Hello, World!</h1></body>
          |</html>
        """.trimMargin() to """
          |<p class="greet">Hello!</p>
        """.trimMargin()

      TargetLanguage.CSS ->
        """
          |.hello::before {
          |  content: "Hello, World!";
          |}
        """.trimMargin() to """
          |.greet {
          |  font-weight: bold;
          |}
        """.trimMargin()

      TargetLanguage.JSON ->
        """
          |{
          |  "message": "Hello, World!"
          |}
        """.trimMargin() to """
          |{
          |  "greet": "Hello"
          |}
        """.trimMargin()

      TargetLanguage.XML, TargetLanguage.YAML ->
        commentStub(language, "Hello, World!") to
          commentStub(language, "greet helper")

      TargetLanguage.KOTLIN ->
        """
          |fun main() {
          |    println("Hello, World!")
          |}
        """.trimMargin() to """
          |fun greet(name: String = "World"): String = "Hello, ${'$'}name!"
        """.trimMargin()

      else ->
        
        """
          |${lineComment(language)} Hello, World! (${language.displayName} fallback)
          |${lineComment(language)} TODO: idiomatic hello for ${language.id}
        """.trimMargin() to """
          |${lineComment(language)} greet helper (${language.id})
        """.trimMargin()
    }

    val imports = when (language) {
      TargetLanguage.GO -> SemanticBlock(
        label = "imports",
        slot = SmartInsertPlanner.Slot.IMPORTS,
        code = "import \"fmt\"",
      )
      TargetLanguage.CPP -> SemanticBlock(
        label = "imports",
        slot = SmartInsertPlanner.Slot.IMPORTS,
        code = "#include <iostream>\n#include <string>",
      )
      else -> null
    }

    return SwarmPlan(
      title = "hello world (${language.id})",
      blocks = listOfNotNull(
        imports,
        SemanticBlock(label = "main", slot = SmartInsertPlanner.Slot.NEAR_CARET, code = main),
        SemanticBlock(label = "greet", slot = SmartInsertPlanner.Slot.FILE_END, code = helper),
      ),
    )
  }

  private fun loginPlan(prompt: String, language: TargetLanguage): SwarmPlan {
    val safe = prompt.replace("\n", " ").take(60)
    return when (language) {
      TargetLanguage.JAVA -> SwarmPlan(
        title = "login (${language.id})",
        blocks = listOf(
          SemanticBlock(
            label = "model",
            slot = SmartInsertPlanner.Slot.NEAR_CARET,
            code = """
              |public static final class LoginState {
              |    public final String email;
              |    public final String password;
              |    public final String error;
              |    public final boolean loading;
              |
              |    public LoginState(String email, String password, String error, boolean loading) {
              |        this.email = email;
              |        this.password = password;
              |        this.error = error;
              |        this.loading = loading;
              |    }
              |}
            """.trimMargin(),
          ),
          SemanticBlock(
            label = "validate",
            slot = SmartInsertPlanner.Slot.CLASS_END,
            code = """
              |public static String validateLogin(String email, String password) {
              |    if (email == null || email.isBlank()) return "Введите email";
              |    if (!email.contains("@")) return "Некорректный email";
              |    if (password == null || password.length() < 6) return "Пароль слишком короткий";
              |    return null;
              |}
            """.trimMargin(),
          ),
          SemanticBlock(
            label = "action",
            slot = SmartInsertPlanner.Slot.CLASS_END,
            code = """
              |public static LoginState onLoginClick(LoginState state, Runnable onSuccess) {
              |    String error = validateLogin(state.email, state.password);
              |    if (error != null) {
              |        return new LoginState(state.email, state.password, error, false);
              |    }
              |    // TODO: $safe
              |    onSuccess.run();
              |    return new LoginState(state.email, state.password, null, false);
              |}
            """.trimMargin(),
          ),
        ),
      )

      TargetLanguage.PYTHON -> SwarmPlan(
        title = "login (${language.id})",
        blocks = listOf(
          SemanticBlock(
            label = "model",
            slot = SmartInsertPlanner.Slot.NEAR_CARET,
            code = """
              |from dataclasses import dataclass
              |from typing import Optional, Callable
              |
              |
              |@dataclass
              |class LoginState:
              |    email: str = ""
              |    password: str = ""
              |    error: Optional[str] = None
              |    loading: bool = False
            """.trimMargin(),
          ),
          SemanticBlock(
            label = "validate",
            slot = SmartInsertPlanner.Slot.FILE_END,
            code = """
              |def validate_login(email: str, password: str) -> Optional[str]:
              |    if not email.strip():
              |        return "Введите email"
              |    if "@" not in email:
              |        return "Некорректный email"
              |    if len(password) < 6:
              |        return "Пароль слишком короткий"
              |    return None
              |
              |
              |def on_login_click(state: LoginState, on_success: Callable[[], None]) -> LoginState:
              |    error = validate_login(state.email, state.password)
              |    if error:
              |        return LoginState(state.email, state.password, error, False)
              |    # TODO: $safe
              |    on_success()
              |    return LoginState(state.email, state.password, None, False)
            """.trimMargin(),
          ),
        ),
      )

      TargetLanguage.JAVASCRIPT, TargetLanguage.TYPESCRIPT -> {
        val tEmail = if (language == TargetLanguage.TYPESCRIPT) ": string" else ""
        val tState = if (language == TargetLanguage.TYPESCRIPT) ": LoginState" else ""
        val tRet = if (language == TargetLanguage.TYPESCRIPT) ": string | null" else ""
        SwarmPlan(
          title = "login (${language.id})",
          blocks = listOf(
            SemanticBlock(
              label = "model",
              slot = SmartInsertPlanner.Slot.NEAR_CARET,
              code = if (language == TargetLanguage.TYPESCRIPT) {
                """
                  |type LoginState = {
                  |  email: string;
                  |  password: string;
                  |  error: string | null;
                  |  loading: boolean;
                  |};
                """.trimMargin()
              } else {
                """
                  |const emptyLoginState = () => ({
                  |  email: "",
                  |  password: "",
                  |  error: null,
                  |  loading: false,
                  |});
                """.trimMargin()
              },
            ),
            SemanticBlock(
              label = "logic",
              slot = SmartInsertPlanner.Slot.FILE_END,
              code = """
                |function validateLogin(email$tEmail, password$tEmail)$tRet {
                |  if (!email.trim()) return "Введите email";
                |  if (!email.includes("@")) return "Некорректный email";
                |  if (password.length < 6) return "Пароль слишком короткий";
                |  return null;
                |}
                |
                |function onLoginClick(state$tState, onSuccess) {
                |  const error = validateLogin(state.email, state.password);
                |  if (error) return { ...state, error, loading: false };
                |  // TODO: $safe
                |  onSuccess();
                |  return { ...state, error: null, loading: false };
                |}
              """.trimMargin(),
            ),
          ),
        )
      }

      TargetLanguage.KOTLIN -> SwarmPlan(
        title = "login (${language.id})",
        blocks = listOf(
          SemanticBlock(
            label = "model",
            slot = SmartInsertPlanner.Slot.NEAR_CARET,
            code = """
              |data class LoginState(
              |    val email: String = "",
              |    val password: String = "",
              |    val error: String? = null,
              |    val loading: Boolean = false,
              |)
            """.trimMargin(),
          ),
          SemanticBlock(
            label = "validate",
            slot = SmartInsertPlanner.Slot.CLASS_END,
            code = """
              |fun validateLogin(email: String, password: String): String? {
              |    if (email.isBlank()) return "Введите email"
              |    if ("@" !in email) return "Некорректный email"
              |    if (password.length < 6) return "Пароль слишком короткий"
              |    return null
              |}
            """.trimMargin(),
          ),
          SemanticBlock(
            label = "action",
            slot = SmartInsertPlanner.Slot.CLASS_END,
            code = """
              |fun onLoginClick(state: LoginState, onSuccess: () -> Unit): LoginState {
              |    val error = validateLogin(state.email, state.password)
              |    if (error != null) return state.copy(error = error, loading = false)
              |    // TODO: $safe
              |    onSuccess()
              |    return state.copy(error = null, loading = false)
              |}
            """.trimMargin(),
          ),
        ),
      )

      TargetLanguage.GO -> SwarmPlan(
        title = "login (${language.id})",
        blocks = listOf(
          SemanticBlock(
            label = "imports",
            slot = SmartInsertPlanner.Slot.IMPORTS,
            code = "import \"strings\"",
          ),
          SemanticBlock(
            label = "model",
            slot = SmartInsertPlanner.Slot.NEAR_CARET,
            code = """
              |type LoginState struct {
              |	Email    string
              |	Password string
              |	Error    string
              |	Loading  bool
              |}
            """.trimMargin(),
          ),
          SemanticBlock(
            label = "logic",
            slot = SmartInsertPlanner.Slot.FILE_END,
            code = """
              |func ValidateLogin(email, password string) string {
              |	if email == "" {
              |		return "Введите email"
              |	}
              |	if !strings.Contains(email, "@") {
              |		return "Некорректный email"
              |	}
              |	if len(password) < 6 {
              |		return "Пароль слишком короткий"
              |	}
              |	return ""
              |}
              |
              |func OnLoginClick(state LoginState, onSuccess func()) LoginState {
              |	if err := ValidateLogin(state.Email, state.Password); err != "" {
              |		state.Error = err
              |		state.Loading = false
              |		return state
              |	}
              |	// TODO: $safe
              |	onSuccess()
              |	state.Error = ""
              |	state.Loading = false
              |	return state
              |}
            """.trimMargin(),
          ),
        ),
      )

      TargetLanguage.RUST -> SwarmPlan(
        title = "login (${language.id})",
        blocks = listOf(
          SemanticBlock(
            label = "model",
            slot = SmartInsertPlanner.Slot.NEAR_CARET,
            code = """
              |struct LoginState {
              |    email: String,
              |    password: String,
              |    error: Option<String>,
              |    loading: bool,
              |}
            """.trimMargin(),
          ),
          SemanticBlock(
            label = "logic",
            slot = SmartInsertPlanner.Slot.FILE_END,
            code = """
              |fn validate_login(email: &str, password: &str) -> Option<&'static str> {
              |    if email.trim().is_empty() {
              |        return Some("Введите email");
              |    }
              |    if !email.contains('@') {
              |        return Some("Некорректный email");
              |    }
              |    if password.len() < 6 {
              |        return Some("Пароль слишком короткий");
              |    }
              |    None
              |}
              |
              |fn on_login_click(mut state: LoginState, on_success: impl FnOnce()) -> LoginState {
              |    if let Some(err) = validate_login(&state.email, &state.password) {
              |        state.error = Some(err.to_string());
              |        state.loading = false;
              |        return state;
              |    }
              |    // TODO: $safe
              |    on_success();
              |    state.error = None;
              |    state.loading = false;
              |    state
              |}
            """.trimMargin(),
          ),
        ),
      )

      TargetLanguage.CSHARP -> SwarmPlan(
        title = "login (${language.id})",
        blocks = listOf(
          SemanticBlock(
            label = "model",
            slot = SmartInsertPlanner.Slot.NEAR_CARET,
            code = """
              |public sealed class LoginState {
              |    public string Email { get; set; } = "";
              |    public string Password { get; set; } = "";
              |    public string? Error { get; set; }
              |    public bool Loading { get; set; }
              |}
            """.trimMargin(),
          ),
          SemanticBlock(
            label = "logic",
            slot = SmartInsertPlanner.Slot.CLASS_END,
            code = """
              |public static string? ValidateLogin(string email, string password) {
              |    if (string.IsNullOrWhiteSpace(email)) return "Введите email";
              |    if (!email.Contains('@')) return "Некорректный email";
              |    if (password.Length < 6) return "Пароль слишком короткий";
              |    return null;
              |}
              |
              |public static LoginState OnLoginClick(LoginState state, Action onSuccess) {
              |    var error = ValidateLogin(state.Email, state.Password);
              |    if (error != null) return new LoginState {
              |        Email = state.Email, Password = state.Password, Error = error, Loading = false
              |    };
              |    // TODO: $safe
              |    onSuccess();
              |    return new LoginState {
              |        Email = state.Email, Password = state.Password, Error = null, Loading = false
              |    };
              |}
            """.trimMargin(),
          ),
        ),
      )

      TargetLanguage.PHP -> SwarmPlan(
        title = "login (${language.id})",
        blocks = listOf(
          SemanticBlock(
            label = "logic",
            slot = SmartInsertPlanner.Slot.NEAR_CARET,
            code = """
              |<?php
              |// TODO: $safe
              |function validate_login(string ${'$'}email, string ${'$'}password): ?string {
              |    if (trim(${'$'}email) === '') return "Введите email";
              |    if (!str_contains(${'$'}email, '@')) return "Некорректный email";
              |    if (strlen(${'$'}password) < 6) return "Пароль слишком короткий";
              |    return null;
              |}
            """.trimMargin(),
          ),
        ),
      )

      TargetLanguage.RUBY -> SwarmPlan(
        title = "login (${language.id})",
        blocks = listOf(
          SemanticBlock(
            label = "logic",
            slot = SmartInsertPlanner.Slot.NEAR_CARET,
            code = """
              |# TODO: $safe
              |def validate_login(email, password)
              |  return "Введите email" if email.to_s.strip.empty?
              |  return "Некорректный email" unless email.include?("@")
              |  return "Пароль слишком короткий" if password.to_s.length < 6
              |  nil
              |end
            """.trimMargin(),
          ),
        ),
      )

      TargetLanguage.SHELL -> SwarmPlan(
        title = "login (${language.id})",
        blocks = listOf(
          SemanticBlock(
            label = "logic",
            slot = SmartInsertPlanner.Slot.NEAR_CARET,
            code = """
              |# TODO: $safe
              |validate_login() {
              |  local email="${'$'}1" password="${'$'}2"
              |  [[ -z "${'$'}email" ]] && { echo "Введите email"; return 1; }
              |  [[ "${'$'}email" != *"@"* ]] && { echo "Некорректный email"; return 1; }
              |  [[ ${'$'}{#password} -lt 6 ]] && { echo "Пароль слишком короткий"; return 1; }
              |  return 0
              |}
            """.trimMargin(),
          ),
        ),
      )

      else -> genericFallbackPlan("login", safe, language)
    }
  }

  private fun testPlan(prompt: String, language: TargetLanguage): SwarmPlan {
    val safe = prompt.replace("\n", " ").take(60)
    return when (language) {
      TargetLanguage.JAVA -> SwarmPlan(
        title = "tests (${language.id})",
        blocks = listOf(
          SemanticBlock(
            label = "subject",
            slot = SmartInsertPlanner.Slot.NEAR_CARET,
            code = """
              |public static int sampleUnderTest(int x) {
              |    return x * 2;
              |}
            """.trimMargin(),
          ),
          SemanticBlock(
            label = "test",
            slot = SmartInsertPlanner.Slot.FILE_END,
            code = """
              |// test: $safe
              |public static void testSampleUnderTest() {
              |    if (sampleUnderTest(21) != 42) throw new IllegalStateException("fail");
              |    if (sampleUnderTest(0) != 0) throw new IllegalStateException("fail");
              |}
            """.trimMargin(),
          ),
        ),
      )
      TargetLanguage.PYTHON -> SwarmPlan(
        title = "tests (${language.id})",
        blocks = listOf(
          SemanticBlock(
            label = "subject",
            slot = SmartInsertPlanner.Slot.NEAR_CARET,
            code = "def sample_under_test(x: int) -> int:\n    return x * 2",
          ),
          SemanticBlock(
            label = "test",
            slot = SmartInsertPlanner.Slot.FILE_END,
            code = """
              |# test: $safe
              |def test_sample_under_test() -> None:
              |    assert sample_under_test(21) == 42
              |    assert sample_under_test(0) == 0
            """.trimMargin(),
          ),
        ),
      )
      TargetLanguage.JAVASCRIPT, TargetLanguage.TYPESCRIPT -> SwarmPlan(
        title = "tests (${language.id})",
        blocks = listOf(
          SemanticBlock(
            label = "subject",
            slot = SmartInsertPlanner.Slot.NEAR_CARET,
            code = "function sampleUnderTest(x${if (language == TargetLanguage.TYPESCRIPT) ": number" else ""})${if (language == TargetLanguage.TYPESCRIPT) ": number" else ""} {\n  return x * 2;\n}",
          ),
          SemanticBlock(
            label = "test",
            slot = SmartInsertPlanner.Slot.FILE_END,
            code = """
              |// test: $safe
              |function testSampleUnderTest() {
              |  console.assert(sampleUnderTest(21) === 42);
              |  console.assert(sampleUnderTest(0) === 0);
              |}
            """.trimMargin(),
          ),
        ),
      )
      TargetLanguage.GO -> SwarmPlan(
        title = "tests (${language.id})",
        blocks = listOf(
          SemanticBlock(
            label = "subject",
            slot = SmartInsertPlanner.Slot.NEAR_CARET,
            code = "func SampleUnderTest(x int) int {\n\treturn x * 2\n}",
          ),
          SemanticBlock(
            label = "test",
            slot = SmartInsertPlanner.Slot.FILE_END,
            code = """
              |// test: $safe
              |func TestSampleUnderTest(t *testing.T) {
              |	if SampleUnderTest(21) != 42 {
              |		t.Fatal("fail")
              |	}
              |	if SampleUnderTest(0) != 0 {
              |		t.Fatal("fail")
              |	}
              |}
            """.trimMargin(),
          ),
        ),
      )
      TargetLanguage.RUST -> SwarmPlan(
        title = "tests (${language.id})",
        blocks = listOf(
          SemanticBlock(
            label = "subject",
            slot = SmartInsertPlanner.Slot.NEAR_CARET,
            code = "fn sample_under_test(x: i32) -> i32 {\n    x * 2\n}",
          ),
          SemanticBlock(
            label = "test",
            slot = SmartInsertPlanner.Slot.FILE_END,
            code = """
              |// test: $safe
              |#[cfg(test)]
              |mod tests {
              |    use super::*;
              |    #[test]
              |    fn test_sample_under_test() {
              |        assert_eq!(sample_under_test(21), 42);
              |        assert_eq!(sample_under_test(0), 0);
              |    }
              |}
            """.trimMargin(),
          ),
        ),
      )
      TargetLanguage.CSHARP -> SwarmPlan(
        title = "tests (${language.id})",
        blocks = listOf(
          SemanticBlock(
            label = "subject",
            slot = SmartInsertPlanner.Slot.NEAR_CARET,
            code = "public static int SampleUnderTest(int x) => x * 2;",
          ),
          SemanticBlock(
            label = "test",
            slot = SmartInsertPlanner.Slot.FILE_END,
            code = """
              |// test: $safe
              |public static void TestSampleUnderTest() {
              |    if (SampleUnderTest(21) != 42) throw new Exception("fail");
              |    if (SampleUnderTest(0) != 0) throw new Exception("fail");
              |}
            """.trimMargin(),
          ),
        ),
      )
      TargetLanguage.KOTLIN -> SwarmPlan(
        title = "tests (${language.id})",
        blocks = listOf(
          SemanticBlock(
            label = "subject",
            slot = SmartInsertPlanner.Slot.NEAR_CARET,
            code = "fun sampleUnderTest(x: Int): Int = x * 2",
          ),
          SemanticBlock(
            label = "test",
            slot = SmartInsertPlanner.Slot.FILE_END,
            code = """
              |// test: $safe
              |fun testSampleUnderTest() {
              |    check(sampleUnderTest(21) == 42)
              |    check(sampleUnderTest(0) == 0)
              |}
            """.trimMargin(),
          ),
        ),
      )
      else -> genericFallbackPlan("test", safe, language)
    }
  }

  private fun defaultPlan(prompt: String, language: TargetLanguage): SwarmPlan {
    val safeComment = prompt.replace("\n", " ").take(80)
    val fn = slugFunctionName(prompt)
    val (near, helper) = when (language) {
      TargetLanguage.JAVA ->
        """
          |// $safeComment
          |public static void $fn() {
          |    // Реализуй по запросу выше
          |}
        """.trimMargin() to """
          |public static boolean ${fn}Helper() {
          |    return true;
          |}
        """.trimMargin()

      TargetLanguage.PYTHON ->
        """
          |# $safeComment
          |def ${fn.snake()}:
          |    # Реализуй по запросу выше
          |    pass
        """.trimMargin() to """
          |def ${fn.snake()}_helper() -> bool:
          |    return True
        """.trimMargin()

      TargetLanguage.JAVASCRIPT, TargetLanguage.TYPESCRIPT ->
        """
          |// $safeComment
          |function $fn() {
          |  // Реализуй по запросу выше
          |}
        """.trimMargin() to """
          |function ${fn}Helper() {
          |  return true;
          |}
        """.trimMargin()

      TargetLanguage.GO ->
        """
          |// $safeComment
          |func $fn() {
          |	// Реализуй по запросу выше
          |}
        """.trimMargin() to """
          |func ${fn}Helper() bool {
          |	return true
          |}
        """.trimMargin()

      TargetLanguage.RUST ->
        """
          |// $safeComment
          |fn ${fn.snake()}() {
          |    // Реализуй по запросу выше
          |}
        """.trimMargin() to """
          |fn ${fn.snake()}_helper() -> bool {
          |    true
          |}
        """.trimMargin()

      TargetLanguage.SHELL ->
        """
          |# $safeComment
          |$fn() {
          |  # Реализуй по запросу выше
          |}
        """.trimMargin() to """
          |${fn}_helper() {
          |  return 0
          |}
        """.trimMargin()

      TargetLanguage.C ->
        """
          |/* $safeComment */
          |void $fn(void) {
          |    /* Реализуй по запросу выше */
          |}
        """.trimMargin() to """
          |int ${fn}_helper(void) {
          |    return 1;
          |}
        """.trimMargin()

      TargetLanguage.CPP ->
        """
          |// $safeComment
          |void $fn() {
          |    // Реализуй по запросу выше
          |}
        """.trimMargin() to """
          |bool ${fn}Helper() {
          |    return true;
          |}
        """.trimMargin()

      TargetLanguage.CSHARP ->
        """
          |// $safeComment
          |public static void $fn() {
          |    // Реализуй по запросу выше
          |}
        """.trimMargin() to """
          |public static bool ${fn}Helper() => true;
        """.trimMargin()

      TargetLanguage.SWIFT ->
        """
          |// $safeComment
          |func $fn() {
          |    // Реализуй по запросу выше
          |}
        """.trimMargin() to """
          |func ${fn}Helper() -> Bool {
          |    return true
          |}
        """.trimMargin()

      TargetLanguage.PHP ->
        """
          |<?php
          |// $safeComment
          |function $fn(): void {
          |    // Реализуй по запросу выше
          |}
        """.trimMargin() to """
          |function ${fn}Helper(): bool {
          |    return true;
          |}
        """.trimMargin()

      TargetLanguage.RUBY ->
        """
          |# $safeComment
          |def ${fn.snake()}
          |  # Реализуй по запросу выше
          |end
        """.trimMargin() to """
          |def ${fn.snake()}_helper
          |  true
          |end
        """.trimMargin()

      TargetLanguage.SQL ->
        """
          |-- $safeComment
          |-- TODO: implement query / procedure for request
          |SELECT 1 AS ok;
        """.trimMargin() to """
          |-- helper placeholder
        """.trimMargin()

      TargetLanguage.HTML ->
        """
          |<!-- $safeComment -->
          |<section>
          |  <h1>Generated</h1>
          |  <p>Реализуй по запросу выше</p>
          |</section>
        """.trimMargin() to """
          |<footer>generated</footer>
        """.trimMargin()

      TargetLanguage.CSS ->
        """
          |/* $safeComment */
          |.generated {
          |  display: block;
          |}
        """.trimMargin() to """
          |.generated-helper {
          |  opacity: 1;
          |}
        """.trimMargin()

      TargetLanguage.JSON ->
        """
          |{
          |  "request": "$safeComment",
          |  "status": "todo"
          |}
        """.trimMargin() to """
          |{
          |  "helper": true
          |}
        """.trimMargin()

      TargetLanguage.XML ->
        """
          |<!-- $safeComment -->
          |<root status="todo"/>
        """.trimMargin() to """
          |<!-- helper -->
        """.trimMargin()

      TargetLanguage.YAML ->
        """
          |# $safeComment
          |status: todo
          |request: generated
        """.trimMargin() to """
          |# helper
          |ok: true
        """.trimMargin()

      TargetLanguage.KOTLIN ->
        """
          |// $safeComment
          |fun $fn() {
          |    // Реализуй по запросу выше
          |}
        """.trimMargin() to """
          |fun ${fn}Helper(): Boolean = true
        """.trimMargin()

      else ->
        """
          |${lineComment(language)} $safeComment
          |${lineComment(language)} TODO (${language.displayName}): реализуй по запросу выше
        """.trimMargin() to """
          |${lineComment(language)} helper (${language.id})
        """.trimMargin()
    }

    return SwarmPlan(
      title = "generated (${language.id})",
      blocks = listOf(
        SemanticBlock(label = "main", slot = SmartInsertPlanner.Slot.NEAR_CARET, code = near),
        SemanticBlock(label = "helper", slot = SmartInsertPlanner.Slot.FILE_END, code = helper),
      ),
    )
  }

  
  private fun genericFallbackPlan(
    kind: String,
    safe: String,
    language: TargetLanguage,
  ): SwarmPlan {
    val c = lineComment(language)
    return SwarmPlan(
      title = "$kind (${language.id})",
      blocks = listOf(
        SemanticBlock(
          label = kind,
          slot = SmartInsertPlanner.Slot.NEAR_CARET,
          code = """
            |$c $kind fallback for ${language.displayName}
            |$c TODO: $safe
            |$c (local template — open API key for full AI generation)
          """.trimMargin(),
        ),
      ),
    )
  }

  private fun lineComment(language: TargetLanguage): String = when (language) {
    TargetLanguage.PYTHON,
    TargetLanguage.SHELL,
    TargetLanguage.RUBY,
    TargetLanguage.YAML,
    -> "#"
    TargetLanguage.SQL -> "--"
    TargetLanguage.HTML, TargetLanguage.XML -> "<!--"
    TargetLanguage.CSS -> "/*"
    else -> "//"
  }

  private fun commentStub(language: TargetLanguage, text: String): String {
    val c = lineComment(language)
    return when (language) {
      TargetLanguage.HTML, TargetLanguage.XML -> "$c $text -->"
      TargetLanguage.CSS -> "$c $text */"
      else -> "$c $text"
    }
  }

  private fun slugFunctionName(prompt: String): String {
    val words = Regex("[a-zA-Zа-яА-Я0-9]+").findAll(prompt)
      .map { it.value.lowercase() }
      .filter { it.length >= 2 }
      .take(3)
      .toList()
    if (words.isEmpty()) return "generatedTask"
    val ascii = words.joinToString("") { w ->
      w.map { ch ->
        when (ch) {
          in 'a'..'z', in '0'..'9' -> ch
          else -> ""
        }
      }.joinToString("")
    }.ifBlank { "task" }
    val base = ascii.take(24)
    return base.replaceFirstChar { it.uppercase() }.let { "do$it" }
  }

  private fun String.snake(): String =
    replace(Regex("([a-z])([A-Z])"), "$1_$2")
      .replace(Regex("[^a-zA-Z0-9]+"), "_")
      .lowercase()
      .trim('_')
      .ifBlank { "task" }
}
