package io.github.daniilpascal.specterai.optim

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.editor.Document
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile


object PsiFunctionScanner {

  fun scan(
    project: Project,
    document: Document,
  ): List<FunctionOptimScanner.FunctionHit>? {
    
    val psiFile = ReadAction.compute<PsiFile?, RuntimeException> {
      PsiDocumentManager.getInstance(project).getPsiFile(document)
    } ?: return null

    val lang = psiFile.language.id.lowercase()
    val name = psiFile.name.lowercase()
    val isJvm =
      lang == "kotlin" || lang == "java" ||
        name.endsWith(".kt") || name.endsWith(".kts") || name.endsWith(".java")
    if (!isJvm) return null

    return try {
      ReadAction.compute<List<FunctionOptimScanner.FunctionHit>, RuntimeException> {
        scanPsi(psiFile, document.text)
      }
    } catch (_: Throwable) {
      null
    }
  }

  private fun scanPsi(
    psiFile: PsiFile,
    text: String,
  ): List<FunctionOptimScanner.FunctionHit> {
    val hits = mutableListOf<FunctionOptimScanner.FunctionHit>()
    val seen = HashSet<Int>()

    fun walk(el: PsiElement) {
      if (isFunctionDeclaration(el)) {
        toHit(el, text)?.let { hit ->
          if (seen.add(hit.nameOffset)) hits += hit
        }
      }
      for (child in el.children) walk(child)
    }

    walk(psiFile)
    return hits.sortedBy { it.nameOffset }
  }

  private fun isFunctionDeclaration(el: PsiElement): Boolean {
    val type = el.node?.elementType?.toString()?.uppercase() ?: return false
    
    if (type.contains("BODY") || type.contains("IDENTIFIER") || type.contains("REFERENCE")) {
      return false
    }
    if (type.contains("LITERAL") || type.contains("LAMBDA") || type.contains("FUNCTION_LITERAL")) {
      return false
    }
    if (type.contains("FUNCTION_TYPE") || type.contains("FUN_KEYWORD")) return false
    
    if (type == "FUN" || type.endsWith(":FUN") || type.contains("FUN_DECLARATION")) return true
    if (type.contains("METHOD") && !type.contains("CALL") && !type.contains("REFERENCE")) {
      return true
    }
    return false
  }

  private fun toHit(
    el: PsiElement,
    text: String,
  ): FunctionOptimScanner.FunctionHit? {
    val range = el.textRange ?: return null
    if (range.isEmpty) return null
    val nameInfo = findName(el) ?: return null
    val (name, nameOffset) = nameInfo
    if (name in SKIP_NAMES) return null

    val bodyInfo = extractBody(el) ?: return null
    val rangeStart = lineStart(text, range.startOffset)
    val rangeEnd = range.endOffset.coerceIn(rangeStart, text.length)
    val body = bodyInfo
    return FunctionOptimScanner.makeHit(
      text, name, nameOffset, rangeStart, rangeEnd, body, OptimLang.JVM,
    )
  }

  private fun findName(el: PsiElement): Pair<String, Int>? {
    
    var best: Pair<String, Int>? = null
    fun consider(node: PsiElement) {
      val type = node.node?.elementType?.toString()?.uppercase().orEmpty()
      if (!type.contains("IDENTIFIER")) return
      val raw = node.text.trim()
      if (raw.isEmpty() || !raw[0].isLetter() && raw[0] != '_') return
      if (raw in KEYWORDS) return
      val offset = node.textRange.startOffset
      
      if (best == null || offset < best!!.second) {
        best = raw to offset
      }
    }

    
    for (child in el.children) {
      consider(child)
      if (child.children.isNotEmpty()) {
        val ct = child.node?.elementType?.toString()?.uppercase().orEmpty()
        if (ct.contains("IDENTIFIER") || ct.contains("NAME") ||
          ct.contains("VALUE_PARAMETER") || ct.contains("TYPE_PARAMETER")
        ) {
          
          if (ct.contains("VALUE_PARAMETER") || ct.contains("PARAMETER_LIST")) continue
          for (gc in child.children) consider(gc)
        }
      }
      val ct = child.node?.elementType?.toString()?.uppercase().orEmpty()
      
      if (ct.contains("BLOCK") || ct.contains("BODY") || ct.contains("EQ") ||
        ct == "EQ" || child.text.trimStart().startsWith("{") ||
        child.text.trimStart().startsWith("=")
      ) {
        break
      }
    }

    
    if (best == null) {
      val prefix = el.text.take(160)
      val m = NAME_FALLBACK.find(prefix) ?: return null
      val group = m.groups[1] ?: m.groups[2] ?: return null
      val n = group.value
      if (n.isBlank()) return null
      val abs = el.textRange.startOffset + group.range.first
      return n to abs
    }
    return best
  }

  
  private fun extractBody(el: PsiElement): String? {
    
    for (child in el.children) {
      val type = child.node?.elementType?.toString()?.uppercase().orEmpty()
      if (type.contains("BLOCK") || type.contains("CODE_BLOCK") ||
        (type.contains("BODY") && !type.contains("FUNCTION_TYPE"))
      ) {
        val t = child.text
        
        val trimmed = t.trim()
        return if (trimmed.startsWith("{") && trimmed.endsWith("}")) {
          trimmed.substring(1, trimmed.length - 1)
        } else {
          t
        }
      }
    }

    
    val text = el.text
    val eq = indexOfExpressionEq(text)
    if (eq >= 0) {
      return text.substring(eq + 1).trim()
    }

    
    val open = text.indexOf('{')
    if (open >= 0) {
      val close = matchingBrace(text, open) ?: return null
      return text.substring(open + 1, close)
    }

    
    return ""
  }

  private fun indexOfExpressionEq(text: String): Int {
    var paren = 0
    var angle = 0
    var inString = false
    var inChar = false
    var i = 0
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
        c == '<' -> angle++
        c == '>' -> if (angle > 0) angle--
        c == '{' -> return -1 
        c == '=' && paren == 0 && angle == 0 -> {
          
          val next = if (i + 1 < text.length) text[i + 1] else '\u0000'
          if (next != '=' && next != '>' && prev != '=') return i
        }
      }
      i++
    }
    return -1
  }

  private fun matchingBrace(text: String, openIndex: Int): Int? {
    if (openIndex < 0 || openIndex >= text.length || text[openIndex] != '{') return null
    var depth = 0
    var i = openIndex
    var inLine = false
    var inBlock = false
    var inString = false
    var inChar = false
    while (i < text.length) {
      val c = text[i]
      val next = if (i + 1 < text.length) text[i + 1] else '\u0000'
      when {
        inLine -> if (c == '\n') inLine = false
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

  private fun lineStart(text: String, offset: Int): Int {
    val at = offset.coerceIn(0, text.length)
    val nl = text.lastIndexOf('\n', (at - 1).coerceAtLeast(0))
    return if (nl < 0) 0 else nl + 1
  }

  private val NAME_FALLBACK = Regex(
    """(?:fun|def|function|fn|func)\s+(?:<[^>]*>\s*)?([A-Za-z_][\w]*)""" +
      """|(?:public|private|protected|static|final|synchronized|abstract|default|\s)+\s+[\w.<>,\[\]?\s]+\s+([A-Za-z_][\w]*)\s*\(""",
  )

  private val SKIP_NAMES = setOf("if", "for", "while", "when", "catch", "get", "set")

  private val KEYWORDS = setOf(
    "fun", "override", "open", "public", "private", "protected", "internal",
    "suspend", "inline", "abstract", "final", "operator", "infix", "tailrec",
    "actual", "expect", "external", "static", "synchronized", "native", "default",
    "class", "object", "interface", "enum", "data", "sealed", "inner", "companion",
  )
}
