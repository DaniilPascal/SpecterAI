package io.github.daniilpascal.specterai.highlight

import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.ui.JBColor
import java.awt.Color
import java.awt.Font
import io.github.daniilpascal.specterai.command.AiCommandSyntax


object AiCommandColors {
  private fun white() = TextAttributes(
    JBColor(Color(40, 40, 40), Color(235, 235, 235)),
    null,
    null,
    null,
    Font.PLAIN,
  )

  private fun softGreen() = TextAttributes(
    JBColor(Color(70, 130, 95), Color(140, 175, 150)),
    null,
    null,
    null,
    Font.PLAIN,
  )

  fun forKind(kind: AiCommandSyntax.Segment.Kind): TextAttributes = when (kind) {
    AiCommandSyntax.Segment.Kind.NAME -> white()
    AiCommandSyntax.Segment.Kind.AI -> softGreen()
    AiCommandSyntax.Segment.Kind.ARGS -> softGreen()
    AiCommandSyntax.Segment.Kind.CALL -> white()
  }
}
