package io.github.daniilpascal.specterai.swarm

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.editor.Document
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiWhiteSpace
import io.github.daniilpascal.specterai.ai.AiCodeTemplates
import io.github.daniilpascal.specterai.ai.TargetLanguage


object PsiInsertPlanner {

  fun plan(
    project: Project,
    document: Document,
    commandOffset: Int,
    blocks: List<AiCodeTemplates.SemanticBlock>,
    language: TargetLanguage = TargetLanguage.UNKNOWN,
  ): List<SmartInsertPlanner.Placement> {
    if (blocks.isEmpty()) return emptyList()
    if (language.isProse) {
      return SmartInsertPlanner.plan(document.text, commandOffset, blocks, language)
    }

    
    val psiFile = ReadAction.compute<PsiFile?, RuntimeException> {
      PsiDocumentManager.getInstance(project).getPsiFile(document)
    } ?: return SmartInsertPlanner.plan(document.text, commandOffset, blocks, language)

    return try {
      ReadAction.compute<List<SmartInsertPlanner.Placement>, RuntimeException> {
        planWithPsi(psiFile, document.text, commandOffset, blocks, language)
      }
    } catch (_: Throwable) {
      SmartInsertPlanner.plan(document.text, commandOffset, blocks, language)
    }
  }

  private fun planWithPsi(
    psiFile: PsiFile,
    documentText: String,
    commandOffset: Int,
    blocks: List<AiCodeTemplates.SemanticBlock>,
    language: TargetLanguage,
  ): List<SmartInsertPlanner.Placement> {
    val offset = commandOffset.coerceIn(0, documentText.length)
    val afterImports = findAfterImportsOffsetPsi(psiFile)
      ?: SmartInsertPlanner.findAfterImportsOffset(documentText)
    val classEnd = findEnclosingTypeInsertOffset(psiFile, offset)
      ?: SmartInsertPlanner.findClassBodyEndOffset(documentText)
    val fileEnd = documentText.length
    val near = offset

    return blocks.map { semantic ->
      val code = semantic.code.trim()
      val rawSlot = semantic.slot ?: classify(code, classEnd != null)
      val slot = when {
        language.isProse && rawSlot == SmartInsertPlanner.Slot.IMPORTS ->
          SmartInsertPlanner.Slot.NEAR_CARET
        language.isProse && rawSlot == SmartInsertPlanner.Slot.CLASS_END ->
          SmartInsertPlanner.Slot.NEAR_CARET
        else -> rawSlot
      }
      val at = when (slot) {
        SmartInsertPlanner.Slot.IMPORTS -> afterImports
        SmartInsertPlanner.Slot.NEAR_CARET -> near
        SmartInsertPlanner.Slot.CLASS_END -> classEnd ?: near
        SmartInsertPlanner.Slot.FILE_END -> fileEnd
      }
      SmartInsertPlanner.Placement(
        offset = at.coerceIn(0, documentText.length),
        block = code,
        slot = slot,
        label = semantic.label.ifBlank { slot.name.lowercase() },
      )
    }
  }

  private fun classify(block: String, hasClass: Boolean): SmartInsertPlanner.Slot {
    val first = block.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() }.orEmpty()
    return when {
      isImportOrPackage(first) -> SmartInsertPlanner.Slot.IMPORTS
      isTopLevelType(first) -> SmartInsertPlanner.Slot.FILE_END
      looksLikeMember(first) && hasClass -> SmartInsertPlanner.Slot.CLASS_END
      else -> SmartInsertPlanner.Slot.NEAR_CARET
    }
  }

  
  fun findAfterImportsOffsetPsi(psiFile: PsiFile): Int? {
    
    
    var lastHeaderEnd = 0
    var sawHeader = false

    fun consider(el: PsiElement) {
      val type = el.node?.elementType?.toString()?.uppercase() ?: return
      val isPkg = type.contains("PACKAGE")
      val isImport = type.contains("IMPORT") && !type.contains("IMPORT_KEYWORD")
      val isImportList = type.contains("IMPORT_LIST") || type == "IMPORTLIST"
      if (isPkg || isImport || isImportList) {
        sawHeader = true
        lastHeaderEnd = maxOf(lastHeaderEnd, el.textRange.endOffset)
      }
    }

    
    for (child in psiFile.children) {
      consider(child)
      
      if (child.children.isNotEmpty()) {
        val type = child.node?.elementType?.toString()?.uppercase().orEmpty()
        if (type.contains("IMPORT") || type.contains("PACKAGE") || type.contains("FILE")) {
          child.children.forEach { consider(it) }
        }
      }
      val t = child.node?.elementType?.toString()?.uppercase().orEmpty()
      val text = child.text.trim()
      if (!sawHeader && (child is PsiWhiteSpace || text.isEmpty() || text.startsWith("//") || text.startsWith("/*"))) {
        lastHeaderEnd = maxOf(lastHeaderEnd, child.textRange.endOffset)
        continue
      }
      if (sawHeader &&
        !t.contains("IMPORT") &&
        !t.contains("PACKAGE") &&
        child !is PsiWhiteSpace &&
        text.isNotEmpty() &&
        !text.startsWith("//")
      ) {
        break
      }
    }

    if (!sawHeader) return null
    return lastHeaderEnd.coerceIn(0, psiFile.textLength)
  }

  
  fun findEnclosingTypeInsertOffset(psiFile: PsiFile, offset: Int): Int? {
    val typeEl = findEnclosingType(psiFile, offset) ?: findFirstType(psiFile) ?: return null
    return insertOffsetForType(typeEl, psiFile)
  }

  fun findEnclosingType(psiFile: PsiFile, offset: Int): PsiElement? {
    val at = psiFile.findElementAt(offset)
      ?: psiFile.findElementAt((offset - 1).coerceAtLeast(0))
      ?: return null

    var best: PsiElement? = null
    var el: PsiElement? = at
    while (el != null && el !is PsiFile) {
      if (isTypeDeclaration(el)) best = el
      el = el.parent
    }
    return best
  }

  private fun findFirstType(psiFile: PsiFile): PsiElement? {
    var found: PsiElement? = null
    fun walk(el: PsiElement) {
      if (found != null) return
      if (isTypeDeclaration(el)) {
        found = el
        return
      }
      for (c in el.children) walk(c)
    }
    walk(psiFile)
    return found
  }

  private fun isTypeDeclaration(el: PsiElement): Boolean {
    val type = el.node?.elementType?.toString()?.uppercase() ?: return false
    
    if (type.contains("BODY") || type.contains("IDENTIFIER") || type.contains("REFERENCE")) {
      return false
    }
    if (type.contains("CLASS_INITIALIZER") || type.contains("ANONYMOUS")) return false
    if (type.contains("TYPEALIAS") || type.contains("TYPE_ALIAS")) return false
    val looksLike =
      type.contains("CLASS") ||
        type.contains("OBJECT_DECLARATION") ||
        type == "OBJECT_DECLARATION" ||
        type.contains("INTERFACE") ||
        (type.contains("ENUM") && !type.contains("ENTRY")) ||
        type == "OBJECT" ||
        type.endsWith("_OBJECT")
    if (!looksLike) return false
    return el.textLength >= 5
  }

  private fun insertOffsetForType(typeEl: PsiElement, psiFile: PsiFile): Int {
    val range = typeEl.textRange
    val text = typeEl.text
    val openRel = indexOfBodyOpenBrace(text)
    if (openRel < 0) {
      
      return range.endOffset.coerceIn(0, psiFile.textLength)
    }

    var depth = 0
    for (i in openRel until text.length) {
      when (text[i]) {
        '{' -> depth++
        '}' -> {
          depth--
          if (depth == 0) {
            val abs = range.startOffset + i
            return lineStartOffset(psiFile, abs).coerceIn(0, psiFile.textLength)
          }
        }
      }
    }
    return range.endOffset.coerceIn(0, psiFile.textLength)
  }

  
  private fun indexOfBodyOpenBrace(text: String): Int {
    
    
    var inString = false
    var inChar = false
    var paren = 0
    for (i in text.indices) {
      val c = text[i]
      val prev = if (i > 0) text[i - 1] else '\u0000'
      when {
        inString -> if (c == '"' && prev != '\\') inString = false
        inChar -> if (c == '\'' && prev != '\\') inChar = false
        c == '"' -> inString = true
        c == '\'' -> inChar = true
        c == '(' -> paren++
        c == ')' -> if (paren > 0) paren--
        c == '{' && paren == 0 -> return i
      }
    }
    return -1
  }

  private fun lineStartOffset(psiFile: PsiFile, offset: Int): Int {
    val text = psiFile.text
    val at = offset.coerceIn(0, text.length)
    val nl = text.lastIndexOf('\n', (at - 1).coerceAtLeast(0))
    return if (nl < 0) 0 else nl + 1
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
}
