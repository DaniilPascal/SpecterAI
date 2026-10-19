package io.github.daniilpascal.specterai.command

import com.intellij.openapi.editor.Document
import com.intellij.openapi.util.Key
import com.intellij.psi.PsiFile
import io.github.daniilpascal.specterai.ai.TypingSpeed


object AiCommandSyntax {

  private val CACHE_KEY = Key.create<Pair<Long, List<Match>>>("Specter.AiCommandHL")
  private val DOC_CACHE_KEY = Key.create<Pair<Long, List<Match>>>("Specter.AiCommandHL.Doc")

  
  val COMMAND_REGEX = Regex(
    """((?:Specter|S)\s*\.\s*AI)(\s*\(\s*"([^"]*)"\s*\))(\s*\.\s*start\s*\(\s*(\d{1,3})?\s*\)\s*;)""",
  )

  private val PREFIX_REGEX = Regex("""\b(?:Specter|S)\b""")

  data class Segment(
    val start: Int,
    val end: Int,
    val kind: Kind,
  ) {
    
    enum class Kind { NAME, AI, ARGS, CALL }
  }

  data class Match(
    val start: Int,
    val end: Int,
    val prompt: String,
    val typingSpeed: Int?,
    val segments: List<Segment>,
    val complete: Boolean,
  )

  fun findAll(text: String): List<Match> =
    findHighlightMatches(text).filter { it.complete && !isCeoCommandSpan(text, it) }

  private fun isCeoCommandSpan(text: String, match: Match): Boolean {
    if (match.start < 0 || match.end > text.length || match.start >= match.end) return false
    return Regex("""(?:Specter|S)\s*\.\s*AI\s*\.\s*CEO""")
      .containsMatchIn(text.substring(match.start, match.end))
  }

  
  fun findHighlightMatches(text: String): List<Match> {
    if (text.isEmpty()) return emptyList()
    val out = mutableListOf<Match>()
    var searchFrom = 0
    while (searchFrom < text.length) {
      val prefix = PREFIX_REGEX.find(text, searchFrom) ?: break
      val parsed = parseFromPrefix(text, prefix.range.first) ?: run {
        searchFrom = prefix.range.last + 1
        continue
      }
      
      if (!looksLikeAiCommand(text, parsed.start, parsed.end)) {
        searchFrom = prefix.range.last + 1
        continue
      }
      
      if (isCeoForm(text, parsed.start, parsed.end)) {
        searchFrom = prefix.range.last + 1
        continue
      }
      out += parsed
      searchFrom = parsed.end.coerceAtLeast(prefix.range.last + 1)
    }
    out += AiCeoSyntax.findHighlightMatches(text).map { AiCeoSyntax.toCommandMatch(it) }
    return out.sortedBy { it.start }
  }

  
  private fun isCeoForm(text: String, start: Int, end: Int): Boolean {
    val after = text.substring(end, minOf(end + 12, text.length))
    return Regex("""^\s*\.\s*C""").containsMatchIn(after) ||
      text.substring(start, end).contains(".CEO") ||
      Regex("""(?:Specter|S)\s*\.\s*AI\s*\.\s*CEO""").containsMatchIn(text.substring(start, end))
  }

  fun findNearCaret(text: String, caret: Int): Match? {
    val matches = findAll(text)
    if (matches.isEmpty()) return null
    return matches
      .filter { it.end <= caret + 2 && it.end >= caret - 2 }
      .maxByOrNull { it.start }
      ?: matches.lastOrNull { it.end <= caret }
  }

  fun overlapsRange(text: String, start: Int, end: Int): Boolean {
    val to = end.coerceAtLeast(start)
    return findHighlightMatches(text).any { cmd ->
      start < cmd.end && to > cmd.start
    }
  }

  fun highlightMatchesFor(file: PsiFile): List<Match> {
    val stamp = file.modificationStamp
    val cached = file.getUserData(CACHE_KEY)
    if (cached != null && cached.first == stamp) return cached.second
    val matches = findHighlightMatches(file.text)
    file.putUserData(CACHE_KEY, stamp to matches)
    return matches
  }

  
  fun highlightMatchesFor(document: Document): List<Match> {
    val stamp = document.modificationStamp
    val cached = document.getUserData(DOC_CACHE_KEY)
    if (cached != null && cached.first == stamp) return cached.second
    val matches = findHighlightMatches(document.immutableCharSequence.toString())
    document.putUserData(DOC_CACHE_KEY, stamp to matches)
    return matches
  }

  fun overlapsRange(file: PsiFile, start: Int, end: Int): Boolean {
    val to = end.coerceAtLeast(start)
    return highlightMatchesFor(file).any { cmd ->
      start < cmd.end && to > cmd.start
    }
  }

  private fun looksLikeAiCommand(text: String, start: Int, end: Int): Boolean {
    val slice = text.substring(start, minOf(end + 12, text.length))
    
    return slice.contains('.') ||
      Regex("""^(?:Specter|S)\s*$""").matches(text.substring(start, end))
  }

  private fun parseFromPrefix(text: String, start: Int): Match? {
    var i = start
    val n = text.length
    fun peek(): Char? = if (i < n) text[i] else null
    fun takeWhile(pred: (Char) -> Boolean): Int {
      val from = i
      while (i < n && pred(text[i])) i++
      return from
    }

    
    if (text.startsWith("Specter", start)) {
      i = start + "Specter".length
    } else if (text.startsWith("S", start) &&
      (start + 1 >= n || !text[start + 1].isLetterOrDigit())
    ) {
      i = start + 1
    } else {
      return null
    }

    takeWhile { it.isWhitespace() }
    if (peek() != '.') {
      return Match(
        start = start,
        end = i,
        prompt = "",
        typingSpeed = null,
        segments = listOf(Segment(start, i, Segment.Kind.NAME)),
        complete = false,
      )
    }
    i++ 
    val nameEnd = i 
    takeWhile { it.isWhitespace() }

    
    val aiStart = i
    when {
      text.startsWith("AI", i) -> i += 2
      peek() == 'A' -> i++
      else -> {
        return Match(
          start = start,
          end = nameEnd,
          prompt = "",
          typingSpeed = null,
          segments = listOf(Segment(start, nameEnd, Segment.Kind.NAME)),
          complete = false,
        )
      }
    }
    val aiEnd = i

    fun nameAndAi(): List<Segment> = listOf(
      Segment(start, nameEnd, Segment.Kind.NAME),
      Segment(aiStart, aiEnd, Segment.Kind.AI),
    )

    takeWhile { it.isWhitespace() }
    if (peek() != '(') {
      return Match(
        start = start,
        end = aiEnd,
        prompt = "",
        typingSpeed = null,
        segments = nameAndAi(),
        complete = false,
      )
    }

    val argsStart = i
    i++ 
    takeWhile { it.isWhitespace() }
    var prompt = ""
    if (peek() == '"') {
      i++ 
      val pStart = i
      while (i < n && text[i] != '"' && text[i] != '\n' && text[i] != '\r') i++
      prompt = text.substring(pStart, i)
      if (peek() == '"') i++ 
    }
    takeWhile { it.isWhitespace() }
    if (peek() == ')') i++
    val argsEnd = i

    takeWhile { it.isWhitespace() }
    if (peek() != '.') {
      return Match(
        start = start,
        end = argsEnd,
        prompt = prompt,
        typingSpeed = null,
        segments = nameAndAi() + Segment(argsStart, argsEnd, Segment.Kind.ARGS),
        complete = false,
      )
    }
    val callStart = i
    i++ 
    takeWhile { it.isWhitespace() }

    
    run {
      var k = 0
      while (k < 5 && i + k < n && text[i + k] == "start"[k]) k++
      if (k == 0) {
        return Match(
          start = start,
          end = argsEnd,
          prompt = prompt,
          typingSpeed = null,
          segments = nameAndAi() + Segment(argsStart, argsEnd, Segment.Kind.ARGS),
          complete = false,
        )
      }
      i += k
    }

    takeWhile { it.isWhitespace() }
    var speed: Int? = null
    if (peek() == '(') {
      i++
      takeWhile { it.isWhitespace() }
      val d0 = i
      takeWhile { it.isDigit() }
      if (i > d0) speed = text.substring(d0, i).toIntOrNull()?.coerceIn(TypingSpeed.MIN, TypingSpeed.MAX)
      takeWhile { it.isWhitespace() }
      if (peek() == ')') i++
    }
    takeWhile { it.isWhitespace() }
    val complete = peek() == ';'
    if (complete) i++
    val callEnd = i

    return Match(
      start = start,
      end = callEnd,
      prompt = prompt,
      typingSpeed = speed,
      segments = nameAndAi() + listOf(
        Segment(argsStart, argsEnd, Segment.Kind.ARGS),
        Segment(callStart, callEnd, Segment.Kind.CALL),
      ),
      complete = complete,
    )
  }
}
