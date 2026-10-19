package io.github.daniilpascal.specterai.highlight

import com.intellij.codeInsight.highlighting.HighlightErrorFilter
import com.intellij.psi.PsiErrorElement
import io.github.daniilpascal.specterai.command.AiCommandSyntax
import io.github.daniilpascal.specterai.settings.AiSettings


class AiCommandHighlightErrorFilter : HighlightErrorFilter() {

  override fun shouldHighlightErrorElement(element: PsiErrorElement): Boolean {
    if (!AiSettings.getInstance().enabled) return true
    val file = element.containingFile ?: return true
    return try {
      !AiCommandSyntax.overlapsRange(
        file,
        element.textRange.startOffset,
        element.textRange.endOffset,
      )
    } catch (_: Throwable) {
      true
    }
  }
}
