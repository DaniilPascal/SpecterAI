package io.github.daniilpascal.specterai.swarm

import io.github.daniilpascal.specterai.ai.AiCodeTemplates
import io.github.daniilpascal.specterai.ai.TargetLanguage

object SmartInsertPlanner {

  enum class Slot {
    IMPORTS,
    NEAR_CARET,
    CLASS_END,
    FILE_END,
  }

  data class Placement(
    val offset: Int,
    val block: String,
    val slot: Slot,
    val label: String = slot.name,
  )

  fun plan(
    documentText: String,
    commandOffset: Int,
    blocks: List<AiCodeTemplates.SemanticBlock>,
    language: TargetLanguage = TargetLanguage.UNKNOWN,
  ): List<Placement> {
    if (blocks.isEmpty()) return emptyList()

    val afterImports = findAfterImportsOffset(documentText)
    val caret = commandOffset.coerceIn(0, documentText.length)
    
    val classEnd = findClassBodyEndOffset(documentText, caret)
    val fileEnd = documentText.length
    val near = caret
    val prose = language.isProse

    return blocks.map { semantic ->
      val code = semantic.code.trim()
      val rawSlot = semantic.slot ?: classify(code, documentText, caret)
      
      val slot = when {
        prose && rawSlot == Slot.IMPORTS -> Slot.NEAR_CARET
        prose && rawSlot == Slot.CLASS_END -> Slot.NEAR_CARET
        else -> rawSlot
      }
      val offset = when (slot) {
        Slot.IMPORTS -> afterImports
        Slot.NEAR_CARET -> near
        Slot.CLASS_END -> classEnd ?: near
        Slot.FILE_END -> fileEnd
      }
      Placement(
        offset = offset,
        block = code,
        slot = slot,
        label = semantic.label.ifBlank { slot.name.lowercase() },
      )
    }
  }

  fun parseSlotTag(tag: String): Slot? {
    val t = tag.trim().lowercase()
    return when {
      t in setOf("imports", "import", "package") -> Slot.IMPORTS
      t in setOf("near", "caret", "cursor", "here", "local") -> Slot.NEAR_CARET
      t in setOf("class", "member", "body", "inside") -> Slot.CLASS_END
      t in setOf("file", "eof", "top", "toplevel", "end") -> Slot.FILE_END
      else -> null
    }
  }

  private fun classify(block: String, documentText: String, caretOffset: Int): Slot {
    val first = block.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() }.orEmpty()
    return when {
      isImportOrPackage(first) -> Slot.IMPORTS
      isTopLevelType(first) -> Slot.FILE_END
      looksLikeMember(first) && findClassBodyEndOffset(documentText, caretOffset) != null -> Slot.CLASS_END
      else -> Slot.NEAR_CARET
    }
  }

  private fun isImportOrPackage(first: String): Boolean =
    first.startsWith("import ") ||
      first.startsWith("package ") ||
      first.startsWith("from ") ||
      first.startsWith("using ") ||
      first.startsWith("#include") ||
      first.startsWith("require(") ||
      first.startsWith("require \"")

  private fun isTopLevelType(first: String): Boolean =
    first.startsWith("class ") ||
      first.startsWith("data class ") ||
      first.startsWith("object ") ||
      first.startsWith("interface ") ||
      first.startsWith("enum class ") ||
      first.startsWith("enum ") ||
      first.startsWith("sealed ") ||
      first.startsWith("struct ") ||
      first.startsWith("public class ") ||
      first.startsWith("public interface ") ||
      first.startsWith("public enum ") ||
      first.startsWith("export class ") ||
      first.startsWith("export function ") ||
      first.startsWith("export default ")

  private fun looksLikeMember(first: String): Boolean {
    val prefixes = listOf(
      "fun ", "private fun ", "internal fun ", "protected fun ", "override fun ",
      "val ", "var ", "private val ", "private var ",
      "@", "suspend fun ",
      "public ", "private ", "protected ", "static ",
      "def ", "async def ", "function ", "fn ", "func ",
      "let ", "const ",
    )
    return prefixes.any { first.startsWith(it) }
  }

  fun findAfterImportsOffset(text: String): Int {
    if (text.isEmpty()) return 0
    var offset = 0
    var lastKeep = 0
    var inHeader = true
    for (line in text.split('\n')) {
      val trimmed = line.trim()
      val lineEnd = offset + line.length
      val next = (lineEnd + 1).coerceAtMost(text.length)
      when {
        trimmed.isEmpty() ||
          trimmed.startsWith("//") ||
          trimmed.startsWith("/*") ||
          trimmed.startsWith("*") ||
          trimmed.startsWith("#") && !trimmed.startsWith("#include") -> {
          if (inHeader) lastKeep = next.coerceAtMost(text.length)
        }
        isImportOrPackage(trimmed) -> {
          inHeader = true
          lastKeep = next.coerceAtMost(text.length)
        }
        else -> break
      }
      offset = next
      if (offset >= text.length) break
    }
    return lastKeep.coerceIn(0, text.length)
  }

  fun findClassBodyEndOffset(text: String): Int? =
    findClassBodyEndOffset(text, caretOffset = null)

  
  fun findClassBodyEndOffset(text: String, caretOffset: Int?): Int? {
    val headers = TYPE_HEADER.findAll(text).toList()
    if (headers.isEmpty()) return null

    data class TypeSpan(val open: Int, val close: Int, val insert: Int)

    fun spanFor(headerStart: Int): TypeSpan? {
      val openBrace = text.indexOf('{', headerStart)
      if (openBrace < 0) return null
      var depth = 0
      var i = openBrace
      while (i < text.length) {
        when (text[i]) {
          '{' -> depth++
          '}' -> {
            depth--
            if (depth == 0) {
              var insert = i
              val lineStart = text.lastIndexOf('\n', i - 1)
              if (lineStart >= 0) insert = lineStart + 1
              return TypeSpan(openBrace, i, insert.coerceIn(0, text.length))
            }
          }
        }
        i++
      }
      return null
    }

    val spans = headers.mapNotNull { spanFor(it.range.first) }
    if (spans.isEmpty()) return null

    val caret = caretOffset?.coerceIn(0, text.length)
    if (caret != null) {
      
      val enclosing = spans
        .filter { caret > it.open && caret <= it.close }
        .minByOrNull { it.close - it.open }
      if (enclosing != null) return enclosing.insert
    }
    return spans.first().insert
  }

  private val TYPE_HEADER = Regex(
    
    """(?m)^[ \t]*(?:public\s+|private\s+|internal\s+|protected\s+|export\s+)?(?:data\s+|sealed\s+|enum\s+|inner\s+|value\s+|static\s+|final\s+|abstract\s+)*(class|object|interface|struct|enum)\s+\w+[^{]*\{""",
  )
}
