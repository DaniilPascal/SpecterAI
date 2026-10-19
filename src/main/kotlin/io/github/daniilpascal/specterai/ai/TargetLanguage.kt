package io.github.daniilpascal.specterai.ai


enum class TargetLanguage(
  val id: String,
  val displayName: String,
  val fenceHint: String,
  val isProse: Boolean = false,
) {
  KOTLIN("kotlin", "Kotlin", "kotlin"),
  JAVA("java", "Java", "java"),
  PYTHON("python", "Python", "python"),
  JAVASCRIPT("javascript", "JavaScript", "javascript"),
  TYPESCRIPT("typescript", "TypeScript", "typescript"),
  GO("go", "Go", "go"),
  RUST("rust", "Rust", "rust"),
  C("c", "C", "c"),
  CPP("cpp", "C++", "cpp"),
  CSHARP("csharp", "C#", "csharp"),
  SWIFT("swift", "Swift", "swift"),
  PHP("php", "PHP", "php"),
  RUBY("ruby", "Ruby", "ruby"),
  SQL("sql", "SQL", "sql"),
  HTML("html", "HTML", "html"),
  CSS("css", "CSS", "css"),
  JSON("json", "JSON", "json"),
  XML("xml", "XML", "xml"),
  YAML("yaml", "YAML", "yaml"),
  SHELL("shell", "Shell", "bash"),
  MARKDOWN("markdown", "Markdown", "markdown", isProse = true),
  TEXT("text", "Plain text", "text", isProse = true),
  UNKNOWN("auto", "auto (from file)", "text"),
  ;

  companion object {
    fun detect(fileName: String, fileSnippet: String = ""): TargetLanguage {
      val name = fileName.substringAfterLast('/').lowercase()
      val ext = name.substringAfterLast('.', missingDelimiterValue = "").lowercase()

      byExtension(ext)?.let { return it }

      
      val head = fileSnippet.take(800)
      return when {
        Regex("""\bpackage\s+[\w.]+|^\s*fun\s+\w+""", RegexOption.MULTILINE).containsMatchIn(head) &&
          !head.contains("public class") -> KOTLIN
        Regex("""\bpublic\s+class\b|\bimport\s+java\.""", RegexOption.MULTILINE).containsMatchIn(head) -> JAVA
        Regex("""^\s*def\s+\w+|^\s*import\s+\w+""", RegexOption.MULTILINE).containsMatchIn(head) -> PYTHON
        else -> UNKNOWN
      }
    }

    private fun byExtension(ext: String): TargetLanguage? = when (ext) {
      "kt", "kts" -> KOTLIN
      "java" -> JAVA
      "py", "pyw" -> PYTHON
      "js", "mjs", "cjs" -> JAVASCRIPT
      "ts", "tsx" -> TYPESCRIPT
      "go" -> GO
      "rs" -> RUST
      "c", "h" -> C
      "cpp", "cc", "cxx", "hpp", "hxx" -> CPP
      "cs" -> CSHARP
      "swift" -> SWIFT
      "php" -> PHP
      "rb" -> RUBY
      "sql" -> SQL
      "html", "htm" -> HTML
      "css", "scss", "sass" -> CSS
      "json" -> JSON
      "xml" -> XML
      "yml", "yaml" -> YAML
      "sh", "bash", "zsh" -> SHELL
      "md", "markdown" -> MARKDOWN
      "txt", "text", "log", "rst" -> TEXT
      else -> null
    }
  }

  fun systemLanguageRules(): String = when (this) {
    TEXT, MARKDOWN -> """
      Output language: $displayName (prose / documentation — NOT source code unless asked).
      Write clear human-readable text for descriptions, notes, specs, README-style content.
      For hello / short notes: a few paragraphs or bullet-like plain lines without markdown fences.
      Prefer ### near and ### file tags. Skip ### imports / ### class unless truly relevant.
      For a separate doc file use ### file:Notes.md (relative path).
    """.trimIndent()

    JAVA -> """
      Output language: Java.
      Emit real, compilable Java (not Kotlin). Use classes/methods/fields with proper braces.
      For "hello world": public class with public static void main(String[] args) { System.out.println("Hello, World!"); }
      ### imports = package/import lines; ### class = members inside an existing class (no class wrapper);
      ### near = caret area; ### file = new top-level types at file end;
      ### file:FooTest.java = content for another relative file (e.g. tests).
    """.trimIndent()

    KOTLIN -> """
      Output language: Kotlin.
      Emit real, runnable Kotlin (not Java). For "hello world": fun main() { println("Hello, World!") }.
      ### imports = package/import; ### class = members for existing class body; ### near / ### file as usual;
      ### file:FooTest.kt = companion/test file (relative path).
    """.trimIndent()

    PYTHON -> """
      Output language: Python 3.
      Emit real Python. For "hello world": print("Hello, World!") or a small def main().
      Prefer ### near / ### file. Use ### imports for import lines only. Avoid ### class unless adding methods to a class body.
      ### file:test_foo.py = another relative file when tests/helpers belong elsewhere.
    """.trimIndent()

    JAVASCRIPT, TYPESCRIPT -> """
      Output language: $displayName.
      Emit real ${displayName} (not Kotlin/Java). For "hello world": console.log("Hello, World!").
      ### imports = import/require; ### near / ### file for the rest. ### class = class body members if inside a class.
    """.trimIndent()

    UNKNOWN -> """
      Infer the language from the current file context and filename.
      Match the same language/style already used in the file. If unclear, prefer plain text near the caret.
    """.trimIndent()

    else -> """
      Output language: $displayName.
      Emit real $displayName content appropriate for a .$id / related source file — not Kotlin unless the file is Kotlin.
      For "hello world", use the idiomatic print/log entry point of $displayName.
      ### imports = imports/includes/package as applicable; ### near = caret; ### class = type body members; ### file = file end.
    """.trimIndent()
  }
}
