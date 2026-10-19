package io.github.daniilpascal.specterai.optim

import com.intellij.openapi.editor.Document
import com.intellij.openapi.project.Project


object FunctionOptimScanner {

  data class FunctionHit(
    val name: String,
    
    val nameOffset: Int,
    
    val signatureLine: Int,
    
    val rangeStart: Int,
    
    val rangeEnd: Int,
    val body: String,
    
    val fullText: String,
    
    val optim: FunctionOptimScore.Breakdown?,
    val lang: OptimLang = OptimLang.GENERIC,
  ) {
    val optimPercent: Int? get() = optim?.percent
  }

  
  fun scan(project: Project?, document: Document): List<FunctionHit> {
    val lang = OptimLang.fromDocument(document)
    if (lang == OptimLang.JVM && project != null && !project.isDisposed) {
      val psiHits = PsiFunctionScanner.scan(project, document)
      if (psiHits != null) return psiHits
    }
    return scan(document.text, lang)
  }

  
  fun scan(text: String, lang: OptimLang = OptimLang.GENERIC): List<FunctionHit> {
    if (text.isBlank()) return emptyList()
    val seen = HashSet<Int>()
    val hits = mutableListOf<FunctionHit>()

    when (lang) {
      OptimLang.PYTHON -> hits += LanguageOptimAdapters.scanPython(text, seen)
      OptimLang.JS -> hits += LanguageOptimAdapters.scanJs(text, seen)
      OptimLang.GO -> hits += LanguageOptimAdapters.scanGo(text, seen)
      OptimLang.C_CPP -> hits += LanguageOptimAdapters.scanCCpp(text, seen)
      OptimLang.JVM -> hits += scanJvmRegex(text, seen)
      OptimLang.GENERIC -> {
        
        
        hits += LanguageOptimAdapters.scanPython(text, seen)
        hits += LanguageOptimAdapters.scanJs(text, seen)
        hits += LanguageOptimAdapters.scanGo(text, seen)
        hits += LanguageOptimAdapters.scanCCpp(text, seen)
        hits += scanRustRegex(text, seen)
        hits += scanJvmRegex(text, seen)
      }
    }

    return hits.sortedBy { it.nameOffset }
  }

  
  fun makeHit(
    text: String,
    name: String,
    nameOffset: Int,
    rangeStart: Int,
    rangeEnd: Int,
    body: String,
    lang: OptimLang,
  ): FunctionHit {
    val line = text.lineNumberAt(nameOffset)
    val start = rangeStart.coerceIn(0, text.length)
    val end = rangeEnd.coerceIn(start, text.length)
    val fileLines = text.count { it == '\n' } + if (text.isEmpty()) 0 else 1
    val ctx = FunctionOptimScore.Context(functionName = name, fileLineCount = fileLines)
    val optim =
      if (isEffectivelyEmpty(body, lang)) null
      else FunctionOptimScore.analyze(body, lang, ctx)
    return FunctionHit(
      name = name,
      nameOffset = nameOffset,
      signatureLine = line,
      rangeStart = start,
      rangeEnd = end,
      body = body,
      fullText = text.substring(start, end),
      optim = optim,
      lang = lang,
    )
  }

  fun isEffectivelyEmpty(body: String, lang: OptimLang = OptimLang.GENERIC): Boolean {
    var i = 0
    val s = body
    while (i < s.length) {
      when {
        s[i].isWhitespace() -> i++
        s.startsWith("//", i) || s.startsWith("#", i) -> {
          while (i < s.length && s[i] != '\n') i++
        }
        s.startsWith("/*", i) -> {
          val end = s.indexOf("*/", i + 2)
          i = if (end < 0) s.length else end + 2
        }
        
        (lang == OptimLang.PYTHON || lang == OptimLang.GENERIC) &&
          s.startsWith("pass", i) && (i + 4 >= s.length || !s[i + 4].isLetterOrDigit()) -> {
          i += 4
        }
        (lang == OptimLang.PYTHON || lang == OptimLang.GENERIC) &&
          s.startsWith("...", i) -> {
          i += 3
        }
        
        (lang == OptimLang.JS || lang == OptimLang.GENERIC) &&
          s.startsWith("undefined", i) && (i + 9 >= s.length || !s[i + 9].isLetterOrDigit()) -> {
          i += 9
        }
        (lang == OptimLang.JS || lang == OptimLang.GENERIC) &&
          s.startsWith("null", i) && (i + 4 >= s.length || !s[i + 4].isLetterOrDigit()) -> {
          i += 4
        }
        else -> return false
      }
    }
    return true
  }

  
  private fun scanJvmRegex(text: String, seen: MutableSet<Int>): List<FunctionHit> {
    val hits = mutableListOf<FunctionHit>()

    fun addBraceBody(name: String, nameOffset: Int, matchStart: Int, searchFrom: Int) {
      if (!seen.add(nameOffset)) return
      val braceOpen = text.indexOf('{', searchFrom)
      if (braceOpen < 0 || braceOpen - searchFrom > 120) {
        seen.remove(nameOffset)
        return
      }
      val eq = text.indexOf('=', searchFrom)
      if (eq in searchFrom until braceOpen) {
        val next = if (eq + 1 < text.length) text[eq + 1] else '\u0000'
        if (next != '=' && next != '>') {
          seen.remove(nameOffset)
          return
        }
      }
      val bodyClose = matchingBrace(text, braceOpen) ?: run {
        seen.remove(nameOffset)
        return
      }
      val body = text.substring(braceOpen + 1, bodyClose)
      val rangeStart = lineStart(text, matchStart.coerceAtMost(nameOffset))
      hits += makeHit(text, name, nameOffset, rangeStart, bodyClose + 1, body, OptimLang.JVM)
    }

    for (match in KOTLIN_EXPR_BODY.findAll(text)) {
      val name = match.groupValues[1]
      val nameOffset = match.groups[1]?.range?.first ?: continue
      if (!seen.add(nameOffset)) continue
      val eqAt = match.range.last
      if (eqAt < 0 || eqAt >= text.length || text[eqAt] != '=') {
        seen.remove(nameOffset)
        continue
      }
      val bodyStart = eqAt + 1
      val bodyEnd = expressionBodyEnd(text, bodyStart)
      val body = text.substring(bodyStart, bodyEnd).trim()
      val rangeStart = lineStart(text, match.range.first.coerceAtMost(nameOffset))
      hits += makeHit(text, name, nameOffset, rangeStart, bodyEnd, body, OptimLang.JVM)
    }

    for (match in KOTLIN_FUN.findAll(text)) {
      val name = match.groupValues[1]
      val nameOffset = match.groups[1]?.range?.first ?: continue
      addBraceBody(name, nameOffset, match.range.first, match.range.last)
    }

    for (match in JAVA_METHOD.findAll(text)) {
      val name = match.groupValues[1]
      if (name in CONTROL_KEYWORDS) continue
      val nameOffset = match.groups[1]?.range?.first ?: continue
      addBraceBody(name, nameOffset, match.range.first, match.range.last)
    }

    return hits
  }

  private fun scanRustRegex(text: String, seen: MutableSet<Int>): List<FunctionHit> {
    val hits = mutableListOf<FunctionHit>()
    for (match in RUST_FN.findAll(text)) {
      val name = match.groupValues[1]
      val nameOffset = match.groups[1]?.range?.first ?: continue
      if (!seen.add(nameOffset)) continue
      val braceOpen = text.indexOf('{', match.range.last)
      if (braceOpen < 0 || braceOpen - match.range.last > 120) {
        seen.remove(nameOffset)
        continue
      }
      val bodyClose = matchingBrace(text, braceOpen) ?: run {
        seen.remove(nameOffset)
        continue
      }
      val body = text.substring(braceOpen + 1, bodyClose)
      val rangeStart = lineStart(text, match.range.first.coerceAtMost(nameOffset))
      hits += makeHit(text, name, nameOffset, rangeStart, bodyClose + 1, body, OptimLang.GENERIC)
    }
    return hits
  }

  private fun expressionBodyEnd(text: String, from: Int): Int {
    var i = from.coerceIn(0, text.length)
    var paren = 0
    var brace = 0
    var inString = false
    var inChar = false
    while (i < text.length) {
      val c = text[i]
      val prev = if (i > 0) text[i - 1] else '\u0000'
      when {
        inString -> if (c == '"' && prev != '\\') inString = false
        inChar -> if (c == '\'' && prev != '\\') inChar = false
        c == '"' -> inString = true
        c == '\'' -> inChar = true
        c == '(' -> paren++
        c == ')' -> if (paren > 0) paren--
        c == '{' -> brace++
        c == '}' -> if (brace > 0) brace--
        c == '\n' && paren == 0 && brace == 0 -> {
          var j = i + 1
          while (j < text.length && (text[j] == ' ' || text[j] == '\t')) j++
          if (j >= text.length) return text.length
          if (text[j] == '\n') {
            i++
            continue
          }
          val rest = text.substring(j, minOf(j + 24, text.length))
          if (EXPR_BODY_STOP.containsMatchIn(rest)) return i
        }
      }
      i++
    }
    return text.length
  }

  private fun lineStart(text: String, offset: Int): Int {
    val at = offset.coerceIn(0, text.length)
    val nl = text.lastIndexOf('\n', (at - 1).coerceAtLeast(0))
    return if (nl < 0) 0 else nl + 1
  }

  private fun matchingBrace(text: String, openIndex: Int): Int? {
    if (openIndex < 0 || openIndex >= text.length || text[openIndex] != '{') return null
    var depth = 0
    var i = openIndex
    var inLine = false
    var inHash = false
    var inBlock = false
    var inString = false
    var inChar = false
    while (i < text.length) {
      val c = text[i]
      val next = if (i + 1 < text.length) text[i + 1] else '\u0000'
      when {
        inLine -> if (c == '\n') inLine = false
        inHash -> if (c == '\n') inHash = false
        inBlock -> if (c == '*' && next == '/') {
          inBlock = false
          i++
        }
        inString -> when (c) {
          '\\' -> i++
          '"' -> inString = false
        }
        inChar -> when (c) {
          '\\' -> i++
          '\'' -> inChar = false
        }
        c == '/' && next == '/' -> {
          inLine = true
          i++
        }
        c == '/' && next == '*' -> {
          inBlock = true
          i++
        }
        c == '#' -> inHash = true
        c == '"' -> inString = true
        c == '\'' -> inChar = true
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

  private fun String.lineNumberAt(offset: Int): Int {
    var line = 0
    val until = offset.coerceIn(0, length)
    for (i in 0 until until) if (this[i] == '\n') line++
    return line
  }

  private val KOTLIN_FUN = Regex(
    """(?m)(?:^|\s)(?:(?:public|private|protected|internal|open|override|suspend|inline|crossinline|noinline|operator|infix|tailrec|external|expect|actual|abstract|final)\s+)*fun\s+(?:<[^>]*>\s*)?([A-Za-z_][\w]*)\s*(?:<[^>]*>)?\s*\([^)]*\)""",
  )

  private val KOTLIN_EXPR_BODY = Regex(
    """(?m)(?:^|\s)(?:(?:public|private|protected|internal|open|override|suspend|inline|crossinline|noinline|operator|infix|tailrec|external|expect|actual|abstract|final)\s+)*fun\s+(?:<[^>]*>\s*)?([A-Za-z_][\w]*)\s*(?:<[^>]*>)?\s*\([^)]*\)\s*(?::\s*[^=\n{]+)?\s*=""",
  )

  private val EXPR_BODY_STOP = Regex(
    """^(?:(?:public|private|protected|internal|open|override|suspend|inline|abstract|final|data|sealed|inner|companion)\s+)*(?:fun|class|object|interface|enum|val|var|init)\b|^}""",
  )

  private val JAVA_METHOD = Regex(
    """(?m)(?:^|\s)(?:(?:public|private|protected|static|final|synchronized|native|abstract|default|strictfp)\s+)+[\w.<>,\[\]?\s]+\s+([A-Za-z_][\w]*)\s*\([^;{]*\)\s*(?:throws\s+[\w.,\s]+)?\s*(?=\{)""",
  )

  private val RUST_FN = Regex(
    """(?m)^\s*(?:pub(?:\([^)]*\))?\s+)?(?:async\s+)?fn\s+([A-Za-z_][\w]*)\s*(?:<[^>]*>)?\s*\([^)]*\)""",
  )

  private val CONTROL_KEYWORDS = setOf(
    "if", "for", "while", "switch", "catch", "return", "new", "class", "interface", "enum",
    "function", "def",
  )
}
