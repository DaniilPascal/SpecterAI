package io.github.daniilpascal.specterai.highlight

import com.intellij.codeInspection.InspectionSuppressor
import com.intellij.codeInspection.SuppressQuickFix
import com.intellij.psi.PsiElement
import io.github.daniilpascal.specterai.command.AiCommandSyntax
import io.github.daniilpascal.specterai.settings.AiSettings


class AiCommandInspectionSuppressor : InspectionSuppressor {

  override fun isSuppressedFor(element: PsiElement, toolId: String): Boolean {
    if (!AiSettings.getInstance().enabled) return false
    val file = element.containingFile ?: return false
    if (!file.isValid) return false
    val range = element.textRange ?: return false
    return try {
      AiCommandSyntax.overlapsRange(file, range.startOffset, range.endOffset)
    } catch (_: Throwable) {
      false
    }
  }

  override fun getSuppressActions(element: PsiElement?, toolId: String): Array<SuppressQuickFix> =
    SuppressQuickFix.EMPTY_ARRAY
}
