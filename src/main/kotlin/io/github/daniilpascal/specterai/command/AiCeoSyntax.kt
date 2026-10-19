package io.github.daniilpascal.specterai.command


object AiCeoSyntax {

  const val SIGNATURE = "CEO SpecterAI Daniil_Pascal (Даниил Машичев)"

  private val PREFIX_REGEX = Regex("""\b(?:Specter|S)\b""")

  data class Match(
    val start: Int,
    val end: Int,
    val segments: List<AiCommandSyntax.Segment>,
    val complete: Boolean,
  )

  fun findCompleteNearCaret(text: String, caret: Int): Match? {
    val matches = findHighlightMatches(text).filter { it.complete }
    if (matches.isEmpty()) return null
    return matches
      .filter { it.end <= caret + 2 && it.end >= caret - 2 }
      .maxByOrNull { it.start }
      ?: matches.lastOrNull { it.end <= caret }
  }

  fun findHighlightMatches(text: String): List<Match> {
    if (text.isEmpty()) return emptyList()
    val out = mutableListOf<Match>()
    var searchFrom = 0
    while (searchFrom < text.length) {
      val prefix = PREFIX_REGEX.find(text, searchFrom) ?: break
      val parsed = parseFromPrefix(text, prefix.range.first)
      if (parsed == null) {
        searchFrom = prefix.range.last + 1
        continue
      }
      out += parsed
      searchFrom = parsed.end.coerceAtLeast(prefix.range.last + 1)
    }
    return out
  }

  fun toCommandMatch(match: Match): AiCommandSyntax.Match =
    AiCommandSyntax.Match(
      start = match.start,
      end = match.end,
      prompt = "",
      typingSpeed = null,
      segments = match.segments,
      complete = match.complete,
    )

  private fun parseFromPrefix(text: String, start: Int): Match? {
    var i = start
    val n = text.length
    fun peek(): Char? = if (i < n) text[i] else null
    fun takeWs() {
      while (i < n && text[i].isWhitespace()) i++
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
    takeWs()
    if (peek() != '.') return null
    i++
    val nameEnd = i
    takeWs()

    val aiStart = i
    when {
      text.startsWith("AI", i) -> i += 2
      peek() == 'A' -> i++
      else -> return null
    }
    val aiEnd = i
    takeWs()

    
    if (peek() != '.') return null
    val callStart = i
    i++
    takeWs()

    var k = 0
    while (k < 3 && i + k < n && text[i + k] == "CEO"[k]) k++
    if (k == 0) return null
    i += k
    takeWs()

    fun segs(end: Int) = listOf(
      AiCommandSyntax.Segment(start, nameEnd, AiCommandSyntax.Segment.Kind.NAME),
      AiCommandSyntax.Segment(aiStart, aiEnd, AiCommandSyntax.Segment.Kind.AI),
      AiCommandSyntax.Segment(callStart, end, AiCommandSyntax.Segment.Kind.CALL),
    )

    if (k < 3) {
      return Match(start, i, segs(i), complete = false)
    }
    if (peek() != '(') {
      return Match(start, i, segs(i), complete = false)
    }
    i++
    takeWs()
    if (peek() == ')') i++
    takeWs()
    val complete = peek() == ';'
    if (complete) i++
    return Match(start, i, segs(i), complete = complete)
  }
}
