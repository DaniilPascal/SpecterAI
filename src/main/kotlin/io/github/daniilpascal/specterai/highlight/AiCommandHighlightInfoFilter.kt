package io.github.daniilpascal.specterai.highlight

import com.intellij.codeInsight.daemon.impl.HighlightInfo
import com.intellij.codeInsight.daemon.impl.HighlightInfoFilter
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.psi.PsiFile
import io.github.daniilpascal.specterai.command.AiCommandSyntax
import io.github.daniilpascal.specterai.settings.AiSettings


class AiCommandHighlightInfoFilter : HighlightInfoFilter {

  override fun accept(highlightInfo: HighlightInfo, file: PsiFile?): Boolean {
    if (file == null || !file.isValid) return true
    if (!AiSettings.getInstance().enabled) return true

    val start = highlightInfo.startOffset
    if (start < 0) return true
    val end = highlightInfo.endOffset.coerceAtLeast(start)

    val overlaps = try {
      AiCommandSyntax.overlapsRange(file, start, end)
    } catch (_: Throwable) {
      false
    }
    if (!overlaps) return true

    val severity = highlightInfo.severity
    return severity == HighlightSeverity.INFORMATION ||
      severity == HighlightSeverity.TEXT_ATTRIBUTES
  }
}
