package io.github.daniilpascal.specterai.optim


internal object LanguageOptimAdapters {

  fun scanPython(text: String, seen: MutableSet<Int>): List<FunctionOptimScanner.FunctionHit> {
    val hits = mutableListOf<FunctionOptimScanner.FunctionHit>()
    for (match in PYTHON_DEF.findAll(text)) {
      val name = match.groupValues[1]
      val nameOffset = match.groups[1]?.range?.first ?: continue
      if (!seen.add(nameOffset)) continue
      val bodyInfo = indentedBodyRange(text, match.range.last + 1)
      if (bodyInfo == null) {
        seen.remove(nameOffset)
        continue
      }
      val rangeStart = lineStart(text, match.range.first.coerceAtMost(nameOffset))
      hits += FunctionOptimScanner.makeHit(
        text, name, nameOffset, rangeStart, bodyInfo.end, bodyInfo.body, OptimLang.PYTHON,
      )
    }
    return hits
  }

  fun scanJs(text: String, seen: MutableSet<Int>): List<FunctionOptimScanner.FunctionHit> {
    val hits = mutableListOf<FunctionOptimScanner.FunctionHit>()

    fun addBrace(name: String, nameOffset: Int, matchStart: Int, searchFrom: Int) {
      if (name in JS_SKIP) return
      if (!seen.add(nameOffset)) return
      val braceOpen = text.indexOf('{', searchFrom)
      if (braceOpen < 0 || braceOpen - searchFrom > 160) {
        
        val arrow = text.indexOf("=>", searchFrom)
        if (arrow in searchFrom until (searchFrom + 120)) {
          val bodyStart = arrow + 2
          val bodyEnd = jsExprBodyEnd(text, bodyStart)
          val body = text.substring(bodyStart, bodyEnd).trim().trimEnd(';')
          val rangeStart = lineStart(text, matchStart.coerceAtMost(nameOffset))
          hits += FunctionOptimScanner.makeHit(
            text, name, nameOffset, rangeStart, bodyEnd, body, OptimLang.JS,
          )
          return
        }
        seen.remove(nameOffset)
        return
      }
      val bodyClose = matchingBrace(text, braceOpen)
      if (bodyClose == null) {
        seen.remove(nameOffset)
        return
      }
      val body = text.substring(braceOpen + 1, bodyClose)
      val rangeStart = lineStart(text, matchStart.coerceAtMost(nameOffset))
      hits += FunctionOptimScanner.makeHit(
        text, name, nameOffset, rangeStart, bodyClose + 1, body, OptimLang.JS,
      )
    }

    for (match in JS_FUNCTION.findAll(text)) {
      val name = match.groupValues[1]
      val nameOffset = match.groups[1]?.range?.first ?: continue
      addBrace(name, nameOffset, match.range.first, match.range.last)
    }
    for (match in JS_ARROW_CONST.findAll(text)) {
      val name = match.groupValues[1]
      val nameOffset = match.groups[1]?.range?.first ?: continue
      if (name in JS_SKIP) continue
      if (!seen.add(nameOffset)) continue
      
      val afterArrow = match.range.last + 1
      val braceOpen = text.indexOf('{', afterArrow)
      if (braceOpen in afterArrow until (afterArrow + 40)) {
        val bodyClose = matchingBrace(text, braceOpen)
        if (bodyClose == null) {
          seen.remove(nameOffset)
          continue
        }
        val body = text.substring(braceOpen + 1, bodyClose)
        val rangeStart = lineStart(text, match.range.first.coerceAtMost(nameOffset))
        hits += FunctionOptimScanner.makeHit(
          text, name, nameOffset, rangeStart, bodyClose + 1, body, OptimLang.JS,
        )
      } else {
        val bodyEnd = jsExprBodyEnd(text, afterArrow)
        val body = text.substring(afterArrow, bodyEnd).trim().trimEnd(';')
        val rangeStart = lineStart(text, match.range.first.coerceAtMost(nameOffset))
        hits += FunctionOptimScanner.makeHit(
          text, name, nameOffset, rangeStart, bodyEnd, body, OptimLang.JS,
        )
      }
    }
    for (match in JS_METHOD.findAll(text)) {
      val name = match.groupValues[1]
      val nameOffset = match.groups[1]?.range?.first ?: continue
      addBrace(name, nameOffset, match.range.first, match.range.last)
    }
    return hits
  }

  fun scanGo(text: String, seen: MutableSet<Int>): List<FunctionOptimScanner.FunctionHit> {
    val hits = mutableListOf<FunctionOptimScanner.FunctionHit>()
    for (match in GO_FUNC.findAll(text)) {
      val name = match.groupValues[1]
      val nameOffset = match.groups[1]?.range?.first ?: continue
      if (!seen.add(nameOffset)) continue
      val braceOpen = text.indexOf('{', match.range.last)
      if (braceOpen < 0 || braceOpen - match.range.last > 160) {
        seen.remove(nameOffset)
        continue
      }
      val bodyClose = matchingBrace(text, braceOpen)
      if (bodyClose == null) {
        seen.remove(nameOffset)
        continue
      }
      val body = text.substring(braceOpen + 1, bodyClose)
      val rangeStart = lineStart(text, match.range.first.coerceAtMost(nameOffset))
      hits += FunctionOptimScanner.makeHit(
        text, name, nameOffset, rangeStart, bodyClose + 1, body, OptimLang.GO,
      )
    }
    return hits
  }

  
  fun scanCCpp(text: String, seen: MutableSet<Int>): List<FunctionOptimScanner.FunctionHit> {
    val hits = mutableListOf<FunctionOptimScanner.FunctionHit>()
    for (match in C_CPP_FUNC.findAll(text)) {
      val name = match.groupValues[1]
      if (name in C_CPP_SKIP) continue
      val nameOffset = match.groups[1]?.range?.first ?: continue
      if (!seen.add(nameOffset)) continue
      val braceOpen = text.indexOf('{', match.range.last)
      if (braceOpen < 0 || braceOpen - match.range.last > 160) {
        seen.remove(nameOffset)
        continue
      }
      
      val semi = text.indexOf(';', match.range.last)
      if (semi in match.range.last until braceOpen) {
        seen.remove(nameOffset)
        continue
      }
      val bodyClose = matchingBrace(text, braceOpen)
      if (bodyClose == null) {
        seen.remove(nameOffset)
        continue
      }
      val body = text.substring(braceOpen + 1, bodyClose)
      val rangeStart = lineStart(text, match.range.first.coerceAtMost(nameOffset))
      hits += FunctionOptimScanner.makeHit(
        text, name, nameOffset, rangeStart, bodyClose + 1, body, OptimLang.C_CPP,
      )
    }
    return hits
  }

  internal data class IndentedBody(val body: String, val end: Int)

  internal fun indentedBodyRange(text: String, headerEnd: Int): IndentedBody? {
    val afterHeader = headerEnd.coerceIn(0, text.length)
    val nl = text.indexOf('\n', afterHeader)
    if (nl < 0) return null
    var i = nl + 1
    val lines = mutableListOf<String>()
    var baseIndent: Int? = null
    var end = i
    while (i < text.length) {
      val lineEnd = text.indexOf('\n', i).let { if (it < 0) text.length else it }
      val line = text.substring(i, lineEnd)
      val indent = line.indexOfFirst { !it.isWhitespace() }
      if (indent < 0) {
        if (baseIndent != null) {
          lines += line
          end = if (lineEnd >= text.length) text.length else lineEnd + 1
        }
        i = if (lineEnd >= text.length) text.length else lineEnd + 1
        if (lineEnd >= text.length) break
        continue
      }
      if (baseIndent == null) {
        if (indent == 0) return null
        baseIndent = indent
      }
      if (indent < baseIndent) break
      lines += line
      end = if (lineEnd >= text.length) text.length else lineEnd + 1
      if (lineEnd >= text.length) break
      i = lineEnd + 1
    }
    if (lines.isEmpty()) return null
    return IndentedBody(lines.joinToString("\n"), end)
  }

  private fun jsExprBodyEnd(text: String, from: Int): Int {
    var i = from.coerceIn(0, text.length)
    while (i < text.length && text[i].isWhitespace()) i++
    var paren = 0
    var bracket = 0
    var brace = 0
    var inString = false
    var quote = '\u0000'
    while (i < text.length) {
      val c = text[i]
      if (inString) {
        if (c == '\\') {
          i += 2
          continue
        }
        if (c == quote) inString = false
        i++
        continue
      }
      when (c) {
        '"', '\'', '`' -> {
          inString = true
          quote = c
        }
        '(' -> paren++
        ')' -> if (paren > 0) paren--
        '[' -> bracket++
        ']' -> if (bracket > 0) bracket--
        '{' -> brace++
        '}' -> if (brace > 0) brace--
        ',', ';' -> if (paren == 0 && bracket == 0 && brace == 0) return i
        '\n' -> if (paren == 0 && bracket == 0 && brace == 0) return i
      }
      i++
    }
    return text.length
  }

  private fun matchingBrace(text: String, openIndex: Int): Int? {
    if (openIndex < 0 || openIndex >= text.length || text[openIndex] != '{') return null
    var depth = 0
    var i = openIndex
    var inLine = false
    var inBlock = false
    var inString = false
    var inTemplate = false
    var quote = '\u0000'
    while (i < text.length) {
      val c = text[i]
      val next = if (i + 1 < text.length) text[i + 1] else '\u0000'
      when {
        inLine -> if (c == '\n') inLine = false
        inBlock -> if (c == '*' && next == '/') {
          inBlock = false
          i++
        }
        inString -> when {
          c == '\\' -> i++
          c == quote -> inString = false
        }
        inTemplate -> when {
          c == '\\' -> i++
          c == '`' -> inTemplate = false
        }
        c == '/' && next == '/' -> {
          inLine = true
          i++
        }
        c == '/' && next == '*' -> {
          inBlock = true
          i++
        }
        c == '"' || c == '\'' -> {
          inString = true
          quote = c
        }
        c == '`' -> inTemplate = true
        c == '{' -> depth++
        c == '}' -> {
          depth--
          if (depth == 0) return i
        }
      }
      i++
    }
    return null
  }

  private fun lineStart(text: String, offset: Int): Int {
    val at = offset.coerceIn(0, text.length)
    val nl = text.lastIndexOf('\n', (at - 1).coerceAtLeast(0))
    return if (nl < 0) 0 else nl + 1
  }

  private val PYTHON_DEF = Regex(
    """(?m)^\s*(?:async\s+)?def\s+([A-Za-z_][\w]*)\s*\([^)]*\)\s*(?:->[^:]*)?:\s*(?:#.*)?$""",
  )

  private val JS_FUNCTION = Regex(
    """(?m)(?:^|\s)(?:export\s+(?:default\s+)?)?(?:async\s+)?function\s*\*?\s+([A-Za-z_][\w]*)\s*\([^)]*\)""",
  )

  private val JS_ARROW_CONST = Regex(
    """(?m)(?:^|\s)(?:export\s+)?(?:const|let|var)\s+([A-Za-z_][\w]*)\s*=\s*(?:async\s+)?(?:\([^)]*\)|[A-Za-z_][\w]*)\s*=>""",
  )

  private val JS_METHOD = Regex(
    """(?m)^\s*(?:async\s+)?(?:get\s+|set\s+)?([A-Za-z_][\w]*)\s*\([^)]*\)\s*\{""",
  )

  private val GO_FUNC = Regex(
    """(?m)^\s*func\s+(?:\([^)]*\)\s*)?([A-Za-z_][\w]*)\s*\([^)]*\)""",
  )

  
  private val C_CPP_FUNC = Regex(
    """(?m)^\s*(?:(?:inline|static|extern|virtual|constexpr|explicit|friend|typename)\s+)*(?:[\w:<>\*&\[\]]+\s+)+([A-Za-z_][\w]*)\s*\([^;{]*\)\s*(?:const\s*)?(?:noexcept(?:\s*\([^)]*\))?\s*)?(?:override\s*)?(?=\{)""",
  )

  private val JS_SKIP = setOf(
    "if", "for", "while", "switch", "catch", "return", "new", "class", "interface",
    "function", "get", "set", "of", "in", "from", "import", "export", "default",
  )

  private val C_CPP_SKIP = setOf(
    "if", "for", "while", "switch", "catch", "return", "new", "class", "struct", "enum",
    "union", "sizeof", "typeof", "alignof", "template", "namespace", "using", "typedef",
    "public", "private", "protected", "operator",
  )
}
