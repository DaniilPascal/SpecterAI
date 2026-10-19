package io.github.daniilpascal.specterai.swarm

import com.intellij.openapi.Disposable
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.markup.HighlighterLayer
import com.intellij.openapi.editor.markup.HighlighterTargetArea
import com.intellij.openapi.editor.markup.RangeHighlighter
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopup
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.ui.popup.JBPopupListener
import com.intellij.openapi.ui.popup.LightweightWindowEvent
import com.intellij.openapi.util.Disposer
import com.intellij.ui.JBColor
import com.intellij.ui.awt.RelativePoint
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import io.github.daniilpascal.specterai.ai.AiCodeTemplates
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Font
import java.awt.Point
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.util.concurrent.atomic.AtomicBoolean
import javax.swing.BorderFactory
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JPanel
import javax.swing.JTextArea
import javax.swing.SwingUtilities


class DiffPreviewSession(
  private val project: Project,
  private val editor: Editor,
  private val title: String,
  private val summaryHtml: String,
  private val previewText: String,
  private val highlightRange: IntRange?,
) : Disposable {

  private val settled = AtomicBoolean(false)
  private var popup: JBPopup? = null
  private var highlighter: RangeHighlighter? = null
  private var onAccept: (() -> Unit)? = null
  private var onReject: (() -> Unit)? = null

  val isOpen: Boolean get() = !settled.get() && popup?.isVisible == true

  fun show(onAccept: () -> Unit, onReject: () -> Unit) {
    this.onAccept = onAccept
    this.onReject = onReject
    paintHighlight()

    val panel = buildPanel()
    val built = JBPopupFactory.getInstance()
      .createComponentPopupBuilder(panel, panel)
      .setTitle(title)
      .setResizable(true)
      .setMovable(true)
      .setRequestFocus(true)
      .setFocusable(true)
      .setCancelOnClickOutside(false)
      .setCancelOnOtherWindowOpen(false)
      .setCancelKeyEnabled(true)
      .setCancelCallback {
        reject("Отклонено")
        true
      }
      .createPopup()

    built.addListener(object : JBPopupListener {
      override fun onClosed(event: LightweightWindowEvent) {
        if (!settled.get()) {
          
          reject("Отклонено (Esc)")
        }
      }
    })

    popup = built
    Disposer.register(this) {
      clearHighlight()
      popup?.cancel()
      popup = null
    }

    val point = RelativePoint(editor.contentComponent, visiblePreviewPoint())
    built.show(point)
  }

  
  fun cancel(reason: String = "Отклонено (Esc)") {
    reject(reason)
  }

  private fun accept() {
    if (!settled.compareAndSet(false, true)) return
    clearHighlight()
    val cb = onAccept
    onAccept = null
    onReject = null
    popup?.cancel()
    popup = null
    cb?.invoke()
    Disposer.dispose(this)
  }

  private fun reject(reason: String) {
    if (!settled.compareAndSet(false, true)) return
    clearHighlight()
    val cb = onReject
    onAccept = null
    onReject = null
    popup?.cancel()
    popup = null
    cb?.invoke()
    Disposer.dispose(this)
  }

  private fun buildPanel(): JPanel {
    val root = JPanel(BorderLayout(0, 8)).apply {
      border = JBUI.Borders.empty(10)
      preferredSize = java.awt.Dimension(480, 360)
    }

    val header = JBLabel("<html><body style='width:440px'>$summaryHtml</body></html>")
    root.add(header, BorderLayout.NORTH)

    val area = JTextArea(previewText).apply {
      isEditable = false
      lineWrap = false
      font = Font(Font.MONOSPACED, Font.PLAIN, 12)
      background = JBColor(Color(245, 245, 248), Color(40, 42, 48))
      foreground = JBColor(Color(30, 30, 35), Color(220, 220, 225))
      caret.isVisible = false
      border = JBUI.Borders.empty(6)
    }
    area.addKeyListener(object : KeyAdapter() {
      override fun keyPressed(e: KeyEvent) {
        when {
          e.keyCode == KeyEvent.VK_ENTER && !e.isShiftDown -> {
            e.consume()
            accept()
          }
          e.keyCode == KeyEvent.VK_ESCAPE -> {
            e.consume()
            reject("Отклонено (Esc)")
          }
        }
      }
    })
    root.add(JBScrollPane(area), BorderLayout.CENTER)

    val acceptBtn = JButton("Accept  (Enter)").apply {
      addActionListener { accept() }
    }
    val rejectBtn = JButton("Reject  (Esc)").apply {
      addActionListener { reject("Отклонено") }
    }
    val buttons = JPanel().apply {
      layout = BoxLayout(this, BoxLayout.X_AXIS)
      border = BorderFactory.createEmptyBorder(4, 0, 0, 0)
      add(Box.createHorizontalGlue())
      add(rejectBtn)
      add(Box.createHorizontalStrut(8))
      add(acceptBtn)
    }
    root.add(buttons, BorderLayout.SOUTH)

    SwingUtilities.invokeLater { acceptBtn.requestFocusInWindow() }
    return root
  }

  private fun paintHighlight() {
    val range = highlightRange ?: return
    val start = range.first.coerceIn(0, editor.document.textLength)
    val end = range.last.coerceIn(start, editor.document.textLength)
    if (start >= end) return
    val attrs = TextAttributes().apply {
      backgroundColor = JBColor(Color(255, 220, 180, 90), Color(120, 80, 40, 100))
      effectColor = JBColor(Color(220, 120, 40), Color(255, 170, 80))
    }
    highlighter = editor.markupModel.addRangeHighlighter(
      start,
      end,
      HighlighterLayer.SELECTION - 1,
      attrs,
      HighlighterTargetArea.EXACT_RANGE,
    )
  }

  private fun clearHighlight() {
    highlighter?.let {
      try {
        if (it.isValid) editor.markupModel.removeHighlighter(it)
      } catch (_: Throwable) {
      }
    }
    highlighter = null
  }

  private fun visiblePreviewPoint(): Point {
    return try {
      val offset = (highlightRange?.first ?: editor.caretModel.offset)
        .coerceIn(0, editor.document.textLength)
      val visual = editor.offsetToVisualPosition(offset)
      val p = editor.visualPositionToXY(visual)
      Point(p.x + 12, p.y + editor.lineHeight + 4)
    } catch (_: Throwable) {
      Point(40, 40)
    }
  }

  override fun dispose() {
    clearHighlight()
  }

  companion object {
    data class PreviewModel(
      val title: String,
      val summaryHtml: String,
      val previewText: String,
      val highlightRange: IntRange?,
    )

    fun buildModel(
      plan: AiCodeTemplates.SwarmPlan,
      multi: MultiFilePlanner.ResolvedPlan,
      replaceRange: Pair<Int, Int>?,
      oldSnippet: String?,
    ): PreviewModel {
      val files = multi.otherFileTargets.mapNotNull { it.relativePath }
      val same = multi.sameFileBlocks.size
      val title = when {
        replaceRange != null -> "Preview · Optim Fix"
        multi.isMultiFile -> "Preview · multi-file (${files.size + 1})"
        else -> "Preview · swarm"
      }
      val summary = buildString {
        append("<b>${escape(plan.title.take(60).ifBlank { "AI plan" })}</b><br>")
        when {
          replaceRange != null ->
            append("Замена выделенного фрагмента · ${plan.blocks.size} блок(ов)<br>")
          multi.isMultiFile -> {
            append("Текущий файл: $same блок(ов)")
            if (files.isNotEmpty()) {
              append(" · новые/другие: ")
              append(files.joinToString(", ") { escape(it) })
            }
            append("<br>")
          }
          else -> append("${plan.blocks.size} блок(ов) в текущем файле<br>")
        }
        if (multi.rejected.isNotEmpty()) {
          append("<span style='color:#c44'>Skip: ${escape(multi.rejected.first().rejectReason.orEmpty().take(60))}</span><br>")
        }
        append("<span style='color:#888'>Файл не изменён, пока не нажмёшь Accept. Esc — отклонить.</span>")
      }
      val preview = buildString {
        if (!oldSnippet.isNullOrBlank() && replaceRange != null) {
          appendLine("——— was ———")
          appendLine(trimSnippet(oldSnippet, 12))
          appendLine("——— will be ———")
        }
        for ((i, block) in plan.blocks.withIndex()) {
          val path = block.targetPath
          val tag = when {
            path != null -> "### file:$path"
            block.slot != null -> "### ${block.slot.name.lowercase()}"
            else -> "### block${i + 1}"
          }
          appendLine(tag)
          appendLine(trimSnippet(block.code, 40))
          if (i < plan.blocks.lastIndex) appendLine()
        }
      }.trimEnd()
      val highlight = replaceRange?.let { it.first until it.second }
      return PreviewModel(title, summary, preview, highlight)
    }

    private fun trimSnippet(text: String, maxLines: Int): String {
      val lines = text.lines()
      if (lines.size <= maxLines) return text.trimEnd()
      return lines.take(maxLines).joinToString("\n") + "\n… (${lines.size - maxLines} more lines)"
    }

    private fun escape(s: String): String =
      s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
  }
}
