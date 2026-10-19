package io.github.daniilpascal.specterai.highlight

import com.intellij.lang.annotation.AnnotationHolder
import com.intellij.lang.annotation.Annotator
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import io.github.daniilpascal.specterai.command.AiCommandSyntax
import io.github.daniilpascal.specterai.settings.AiSettings
import io.github.daniilpascal.specterai.swarm.SwarmActivity


class AiCommandAnnotator : Annotator {

  override fun annotate(element: PsiElement, holder: AnnotationHolder) {
    if (!AiSettings.getInstance().enabled) return
    
    if (SwarmActivity.isActive) return
    if (!element.isValid) return
    
    if (element.firstChild != null) return

    val elRange = element.textRange ?: return
    if (elRange.isEmpty) return

    val file = element.containingFile ?: return
    for (match in AiCommandSyntax.highlightMatchesFor(file)) {
      if (elRange.endOffset <= match.start || elRange.startOffset >= match.end) continue
      for (seg in match.segments) {
        val from = maxOf(seg.start, elRange.startOffset)
        val to = minOf(seg.end, elRange.endOffset)
        if (from >= to) continue
        holder.newSilentAnnotation(HighlightSeverity.INFORMATION)
          .range(TextRange(from, to))
          .enforcedTextAttributes(AiCommandColors.forKind(seg.kind))
          .create()
      }
    }
  }
}
