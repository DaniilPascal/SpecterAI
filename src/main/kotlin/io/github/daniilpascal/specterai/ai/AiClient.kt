package io.github.daniilpascal.specterai.ai

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import io.github.daniilpascal.specterai.settings.AiSettings
import io.github.daniilpascal.specterai.swarm.MultiFilePlanner
import io.github.daniilpascal.specterai.swarm.SmartInsertPlanner
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.CompletableFuture

object AiClient {

  private val http = HttpClient.newBuilder()
    .connectTimeout(Duration.ofSeconds(20))
    .build()

  private val gson = Gson()

  
  private const val ABSOLUTE_MAX_TOKENS = 4096
  private const val MIN_USEFUL_TOKENS = 256

  fun generateBlocks(
    prompt: String,
    fileContext: String,
    language: TargetLanguage = TargetLanguage.UNKNOWN,
  ): AiCodeTemplates.SwarmPlan =
    generateBlocksAsync(prompt, fileContext, language).join()

  fun generateBlocksAsync(
    prompt: String,
    fileContext: String,
    language: TargetLanguage = TargetLanguage.UNKNOWN,
  ): CompletableFuture<AiCodeTemplates.SwarmPlan> {
    val scale = RequestScale.planFor(prompt)
    val tokens = scale.maxTokens.coerceAtMost(ABSOLUTE_MAX_TOKENS)
    return requestPlan(prompt, fileContext, language, scale, tokens, creditRetriesLeft = 4)
  }

  private fun requestPlan(
    prompt: String,
    fileContext: String,
    language: TargetLanguage,
    scale: RequestScale.Plan,
    maxTokens: Int,
    creditRetriesLeft: Int,
  ): CompletableFuture<AiCodeTemplates.SwarmPlan> {
    val settings = AiSettings.getInstance()
    val apiKey = settings.getApiKey()
    require(apiKey.isNotBlank()) { "API key is empty. Open Settings → SpecterAI" }

    val blockBudget = minOf(settings.maxBlocks, scale.preferredBlocks).coerceAtLeast(1)
    val proseNote = if (language.isProse) {
      "This is a text/documentation file — write prose, not Kotlin/Java source."
    } else {
      "Write ONLY in ${language.displayName}. Do not switch to another language."
    }

    val tokenHint = if (maxTokens <= 1200) {
      "Token budget is tight ($maxTokens). Write a SHORT but COMPLETE program — fewer classes, full braces."
    } else {
      "Token budget: up to $maxTokens completion tokens. Stay complete; prefer shorter finished code."
    }

    val system = """
      You are Specter.AI inside IntelliJ IDEA.
      The user invoked you from a ${language.displayName} file. $proseNote
      Match output SIZE to the user request (see Scope below). Do not over-generate for tiny asks,
      and do not under-generate for large feature requests.

      ${language.systemLanguageRules()}

      ${scale.sizeInstruction}

      $tokenHint

      Completeness rules (mandatory):
      - Output MUST be syntactically complete: balanced {}, (), [], quotes.
      - NEVER end mid-word (bad: ServerSoc). NEVER leave half a statement.
      - Prefer a smaller finished program over a larger truncated one.
      - If the ask is a system (VPN, proxy, server, chat) ship a runnable educational demo
        with real control flow (listen/accept/read/write/forward), not an empty main.

      Split into 1-$blockBudget independent blocks for parallel multi-cursor typing
      (use fewer blocks for tiny/small scopes).
      Each block MUST start with a tag line exactly like:
      ### imports
      ### near
      ### class
      ### file
      ### file:FooTest.kt
      ### file:src/test/kotlin/FooTest.kt
      Meaning:
      - imports = package/import/include/using lines only (skip for plain text)
      - near = insert near the user command/caret
      - class = members for an existing type/class body (no outer type wrapper; skip for text)
      - file = new top-level content at end of the CURRENT file
      - file:relative/path = content for ANOTHER file (relative to current dir or project).
        Use when the user asks for tests, companion files, or split modules.
        Paths must be relative (no .., no absolute). Prefer short names like FooTest.kt next to the source.
      After the tag line, put ONLY the content for that block (no chat).
      Forbidden: markdown fences, prose about the code, bullet lists, "here is", explanations.
    """.trimIndent()

    val user = buildString {
      appendLine("Request: $prompt")
      appendLine()
      appendLine("Target language: ${language.displayName} (${language.id})")
      appendLine("Detected scope: ${scale.tier} (~${scale.targetLines.first}–${scale.targetLines.last} lines).")
      if (fileContext.isNotBlank()) {
        appendLine()
        appendLine("Current file context:")
        appendLine(fileContext.take(scale.contextChars))
      }
    }

    val body = JsonObject().apply {
      addProperty("model", settings.model)
      add("messages", JsonArray().apply {
        add(JsonObject().apply {
          addProperty("role", "system")
          addProperty("content", system)
        })
        add(JsonObject().apply {
          addProperty("role", "user")
          addProperty("content", user)
        })
      })
      addProperty("temperature", scale.temperature)
      addProperty("max_tokens", maxTokens)
    }

    val endpoint = settings.baseUrl.trimEnd('/') + "/chat/completions"
    val timeoutSec = when (scale.tier) {
      RequestScale.Tier.TINY, RequestScale.Tier.SMALL -> 60L
      RequestScale.Tier.MEDIUM -> 90L
      RequestScale.Tier.LARGE -> 120L
      RequestScale.Tier.XLARGE -> 150L
    }
    val request = HttpRequest.newBuilder()
      .uri(URI.create(endpoint))
      .timeout(Duration.ofSeconds(timeoutSec))
      .header("Content-Type", "application/json")
      .header("Authorization", "Bearer $apiKey")
      .header("HTTP-Referer", "https://specterai.local")
      .header("X-Title", "SpecterAI")
      .POST(HttpRequest.BodyPublishers.ofString(gson.toJson(body)))
      .build()

    return http.sendAsync(request, HttpResponse.BodyHandlers.ofString())
      .thenCompose { response ->
        val code = response.statusCode()
        val rawBody = response.body()

        if (code == 402) {
          val afford = parseAffordableTokens(rawBody)
          val next = when {
            afford != null && afford >= MIN_USEFUL_TOKENS ->
              afford.coerceAtMost(maxTokens - 1).coerceAtMost(ABSOLUTE_MAX_TOKENS)
            maxTokens > MIN_USEFUL_TOKENS * 2 -> maxTokens / 2
            else -> null
          }
          if (next != null && next < maxTokens && creditRetriesLeft > 0) {
            return@thenCompose requestPlan(
              prompt, fileContext, language, scale,
              maxTokens = next.coerceAtLeast(MIN_USEFUL_TOKENS),
              creditRetriesLeft = creditRetriesLeft - 1,
            )
          }
          return@thenCompose CompletableFuture.failedFuture(
            CreditsException(
              "Не хватает кредитов OpenRouter (402). " +
                (afford?.let { "Доступно ~$it token(s). " }.orEmpty()) +
                "Пополни баланс: https://openrouter.ai/settings/credits",
            ),
          )
        }

        if (code !in 200..299) {
          return@thenCompose CompletableFuture.failedFuture(
            IllegalStateException("AI HTTP $code: ${rawBody.take(500)}"),
          )
        }

        val root = gson.fromJson(rawBody, JsonObject::class.java)
        val choice = root.getAsJsonArray("choices")?.get(0)?.asJsonObject
        val finishReason = choice?.get("finish_reason")?.asString.orEmpty()
        val content = choice
          ?.getAsJsonObject("message")
          ?.get("content")
          ?.asString
          ?.trim()
          .orEmpty()

        if (content.isBlank()) {
          return@thenCompose CompletableFuture.failedFuture(IllegalStateException("Empty AI response"))
        }

        val truncated = finishReason.equals("length", ignoreCase = true) || looksTruncated(content)
        if (truncated) {
          return@thenCompose CompletableFuture.failedFuture(
            IllegalStateException(
              "AI обрезал ответ (finish=$finishReason, max_tokens=$maxTokens). " +
                "Пополни кредиты / выбери более дешёвую модель (Fast) или упрости запрос.",
            ),
          )
        }

        val blocks = parseSemanticBlocks(content, blockBudget, language)
        if (blocks.isEmpty()) {
          return@thenCompose CompletableFuture.failedFuture(IllegalStateException("AI returned no code blocks"))
        }
        if (blocks.any { looksTruncated(it.code) }) {
          return@thenCompose CompletableFuture.failedFuture(
            IllegalStateException("AI вернул обрезанный код. Повтори запрос или смени модель."),
          )
        }

        CompletableFuture.completedFuture(
          AiCodeTemplates.SwarmPlan(
            title = "AI: ${settings.model} · ${language.id} · ${scale.tier} · ${maxTokens}tok",
            blocks = blocks,
          ),
        )
      }
  }

  fun isCreditsError(error: Throwable?): Boolean {
    var t = error
    while (t != null) {
      if (t is CreditsException) return true
      val m = t.message?.lowercase().orEmpty()
      if ("402" in m || "credits" in m || "afford" in m || "не хватает кредит" in m) return true
      t = t.cause
    }
    return false
  }

  private fun parseAffordableTokens(body: String): Int? {
    val m = Regex("""can only afford\s+(\d+)""", RegexOption.IGNORE_CASE).find(body)
      ?: Regex("""afford\s+(\d+)""", RegexOption.IGNORE_CASE).find(body)
    return m?.groupValues?.getOrNull(1)?.toIntOrNull()
  }

  
  private fun looksTruncated(code: String): Boolean {
    val t = code.trim()
    if (t.isEmpty()) return true
    val last = t.last()
    if (last.isLetterOrDigit() || last == '_' || last == '.') {
      val tail = t.takeLast(48)
      if (Regex("""\.\s*[A-Z][A-Za-z0-9_]{0,20}$""").containsMatchIn(tail)) return true
      if (Regex("""\b[A-Z][a-z]+[A-Z][A-Za-z0-9_]{0,20}$""").containsMatchIn(tail) &&
        !t.endsWith(";") && !t.endsWith("}") && !t.endsWith(")")
      ) {
        return true
      }
    }
    if (last == '(' || last == '{' || last == '[' || last == ',' || last == '=') return true
    var braces = 0
    var parens = 0
    for (c in t) {
      when (c) {
        '{' -> braces++
        '}' -> braces--
        '(' -> parens++
        ')' -> parens--
      }
    }
    return braces > 0 || parens > 0
  }

  class CreditsException(message: String) : IllegalStateException(message)

  fun parseSemanticBlocks(content: String): List<AiCodeTemplates.SemanticBlock> =
    parseSemanticBlocks(content, AiSettings.getInstance().maxBlocks, TargetLanguage.UNKNOWN)

  fun parseSemanticBlocks(
    content: String,
    limit: Int,
    language: TargetLanguage = TargetLanguage.UNKNOWN,
  ): List<AiCodeTemplates.SemanticBlock> {
    var text = content.trim()
    text = stripLeadingFence(text)
    text = text.replace(Regex("```\\s*$"), "")
    val blockLimit = limit.coerceIn(1, 4)

    
    val tagged = Regex("(?m)^\\s*###\\s*([A-Za-z][\\w\\-]*)(?:\\s*:\\s*(.+?))?\\s*$")
    val matches = tagged.findAll(text).toList()
    if (matches.isNotEmpty()) {
      val blocks = mutableListOf<AiCodeTemplates.SemanticBlock>()
      for (i in matches.indices) {
        val tag = matches[i].groupValues[1]
        val pathRaw = matches[i].groupValues.getOrNull(2)?.trim()?.takeIf { it.isNotEmpty() }
        val start = matches[i].range.last + 1
        val end = matches.getOrNull(i + 1)?.range?.first ?: text.length
        val code = sanitizeCode(text.substring(start, end))
        if (code.isBlank()) continue
        val slot = SmartInsertPlanner.parseSlotTag(tag)
          ?: if (pathRaw != null) SmartInsertPlanner.Slot.FILE_END else null
        val targetPath = if (tag.equals("file", ignoreCase = true)) {
          pathRaw?.let { MultiFilePlanner.sanitizeRelativePath(it) }
        } else {
          null
        }
        val label = when {
          targetPath != null -> "file:${targetPath.substringAfterLast('/')}"
          else -> tag.lowercase()
        }
        blocks += AiCodeTemplates.SemanticBlock(
          code = code,
          slot = slot,
          label = label,
          targetPath = targetPath,
        )
      }
      if (blocks.isNotEmpty()) return blocks.take(blockLimit)
    }

    val bySep = text.split(Regex("(?m)^\\s*###\\s*$"))
      .map { sanitizeCode(it) }
      .filter { it.isNotBlank() }
    if (bySep.size >= 2) {
      return bySep.take(blockLimit).map { AiCodeTemplates.SemanticBlock(code = it) }
    }

    val fenceBlocks = Regex("```[\\w.+-]*\\s*([\\s\\S]*?)```", RegexOption.IGNORE_CASE)
      .findAll(content)
      .map { sanitizeCode(it.groupValues[1]) }
      .filter { it.isNotBlank() }
      .toList()
    if (fenceBlocks.isNotEmpty()) {
      return fenceBlocks.take(blockLimit).map { AiCodeTemplates.SemanticBlock(code = it) }
    }

    val cleaned = sanitizeCode(text)
    if (language.isProse) {
      return if (cleaned.isNotBlank()) {
        listOf(AiCodeTemplates.SemanticBlock(cleaned, label = "text", slot = SmartInsertPlanner.Slot.NEAR_CARET))
      } else {
        emptyList()
      }
    }

    val parts = cleaned.split(Regex("\n\\s*\n"))
      .map { it.trim() }
      .filter { it.isNotBlank() && looksLikeContent(it, language) }
    return when {
      parts.size >= 2 -> listOf(
        AiCodeTemplates.SemanticBlock(parts.first(), label = "part1"),
        AiCodeTemplates.SemanticBlock(parts.drop(1).joinToString("\n\n"), label = "part2"),
      )
      cleaned.isNotBlank() -> listOf(AiCodeTemplates.SemanticBlock(cleaned, label = "code"))
      else -> emptyList()
    }
  }

  private fun sanitizeCode(code: String): String {
    var t = stripFences(code)
    t = t.lineSequence()
      .filterNot { line ->
        val s = line.trim()
        s.startsWith("Here is", ignoreCase = true) ||
          s.startsWith("Here's", ignoreCase = true) ||
          s.startsWith("Sure,", ignoreCase = true) ||
          s.startsWith("Of course", ignoreCase = true) ||
          s.matches(Regex("""^(Explanation|Note|Output)\s*:.*""", RegexOption.IGNORE_CASE)) ||
          s.matches(Regex("""^\d+\.\s+.*"""))
      }
      .joinToString("\n")
      .trim()
    return t
  }

  private fun looksLikeContent(block: String, language: TargetLanguage): Boolean {
    if (language.isProse) return block.isNotBlank()
    val first = block.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() }.orEmpty()
    if (first.startsWith("//") || first.startsWith("/*") || first.startsWith("#") || first.startsWith("*")) {
      return true
    }
    val prefixes = listOf(
      "package ", "import ", "from ", "using ", "include ", "#include",
      "fun ", "def ", "function ", "fn ", "func ",
      "class ", "data class ", "object ", "interface ", "struct ", "enum ", "sealed ",
      "public ", "private ", "protected ", "internal ", "override ",
      "val ", "var ", "let ", "const ", "type ", "typealias ",
      "suspend ", "@", "export ", "module ", "SELECT ", "CREATE ",
      "<", "{",
    )
    return prefixes.any { first.startsWith(it, ignoreCase = true) } || first.length >= 8
  }

  private fun stripLeadingFence(text: String): String =
    text.replace(Regex("^```[\\w.+-]*\\s*", RegexOption.IGNORE_CASE), "")

  private fun stripFences(code: String): String {
    var t = code.trim()
    t = stripLeadingFence(t)
    t = t.replace(Regex("```\\s*$"), "")
    return t.trim()
  }
}
