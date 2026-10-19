package io.github.daniilpascal.specterai.command

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.markup.HighlighterLayer
import com.intellij.openapi.editor.markup.HighlighterTargetArea
import com.intellij.openapi.editor.markup.RangeHighlighter
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.openapi.project.Project
import com.intellij.ui.JBColor
import com.intellij.util.Alarm
import java.awt.Color
import java.awt.Font
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.random.Random


class CommandVanishAnimator(
  private val project: Project,
  private val editor: Editor,
) {
  private val alarm = Alarm(Alarm.ThreadToUse.SWING_THREAD, project)
  private val cancelled = AtomicBoolean(false)
  private val finished = AtomicBoolean(false)
  private var highlighter: RangeHighlighter? = null
  private var onDone: ((anchor: Int) -> Unit)? = null
  private var rangeStart = 0
  private var rangeEnd = 0
  private var phase = 0
  private var pulseTick = 0

  fun start(startOffset: Int, endOffset: Int, onDone: (anchor: Int) -> Unit) {
    this.onDone = onDone
    rangeStart = startOffset.coerceIn(0, editor.document.textLength)
    rangeEnd = endOffset.coerceIn(rangeStart, editor.document.textLength)
    if (rangeStart >= rangeEnd || editor.isDisposed || project.isDisposed) {
      complete(rangeStart)
      return
    }
    paintGlow(0)
    
    alarm.addRequest({ tick() }, 16)
  }

  fun cancel() {
    if (finished.get()) return
    cancelled.set(true)
    alarm.cancelAllRequests()
    val run = {
      clearGlow()
      
      completeOnce(null)
    }
    if (ApplicationManager.getApplication().isDispatchThread) run()
    else ApplicationManager.getApplication().invokeLater(run)
  }

  private fun tick() {
    if (cancelled.get() || project.isDisposed || editor.isDisposed) {
      clearGlow()
      completeOnce(null)
      return
    }
    if (rangeStart >= rangeEnd || rangeStart >= editor.document.textLength) {
      clearGlow()
      complete(rangeStart.coerceIn(0, editor.document.textLength))
      return
    }
    rangeEnd = rangeEnd.coerceAtMost(editor.document.textLength)
    rangeStart = rangeStart.coerceIn(0, rangeEnd)

    when {
      pulseTick < PULSE_TICKS -> {
        paintGlow(pulseTick)
        pulseTick++
        alarm.addRequest({ tick() }, PULSE_DELAY_MS)
      }
      phase == 0 -> {
        
        scramblePass()
        phase = 1
        alarm.addRequest({ tick() }, SCRAMBLE_DELAY_MS)
      }
      else -> {
        
        eraseInward()
        paintGlow(pulseTick + phase)
        if (rangeStart >= rangeEnd) {
          clearGlow()
          complete(rangeStart.coerceIn(0, editor.document.textLength))
        } else {
          alarm.addRequest({ tick() }, ERASE_DELAY_MS)
        }
      }
    }
  }

  private fun scramblePass() {
    if (rangeStart >= rangeEnd) return
    try {
      WriteCommandAction.runWriteCommandAction(project) {
        val doc = editor.document
        val end = rangeEnd.coerceAtMost(doc.textLength)
        val start = rangeStart.coerceIn(0, end)
        if (start >= end) return@runWriteCommandAction
        val original = doc.charsSequence.subSequence(start, end).toString()
        val scrambled = buildString(original.length) {
          for (ch in original) {
            append(
              when {
                ch == '\n' || ch == '\r' -> ch
                ch.isWhitespace() -> ch
                else -> GLITCH[Random.nextInt(GLITCH.length)]
              },
            )
          }
        }
        doc.replaceString(start, end, scrambled)
      }
    } catch (_: Throwable) {
      
    }
  }

  private fun eraseInward() {
    try {
      WriteCommandAction.runWriteCommandAction(project) {
        val doc = editor.document
        rangeEnd = rangeEnd.coerceAtMost(doc.textLength)
        rangeStart = rangeStart.coerceIn(0, rangeEnd)
        if (rangeStart >= rangeEnd) return@runWriteCommandAction

        val len = rangeEnd - rangeStart
        val bite = when {
          len <= 4 -> len
          len <= 12 -> 2
          else -> (len / 8).coerceIn(2, 6)
        }
        
        val right = bite.coerceAtMost(rangeEnd - rangeStart)
        if (right > 0) {
          doc.deleteString(rangeEnd - right, rangeEnd)
          rangeEnd -= right
        }
        val left = (bite - 1).coerceAtLeast(1).coerceAtMost(rangeEnd - rangeStart)
        if (left > 0 && rangeStart < rangeEnd) {
          doc.deleteString(rangeStart, rangeStart + left)
          rangeEnd -= left
        }
      }
    } catch (_: Throwable) {
      rangeEnd = rangeStart
    }
  }

  private fun paintGlow(tick: Int) {
    clearGlow()
    if (editor.isDisposed) return
    rangeEnd = rangeEnd.coerceAtMost(editor.document.textLength)
    rangeStart = rangeStart.coerceIn(0, rangeEnd)
    if (rangeStart >= rangeEnd) return

    val palette = PULSE_COLORS
    val color = palette[tick % palette.size]
    val bg = Color(color.red, color.green, color.blue, 70)
    val attrs = TextAttributes(
      JBColor(color, color),
      JBColor(bg, bg),
      color,
      com.intellij.openapi.editor.markup.EffectType.LINE_UNDERSCORE,
      Font.BOLD,
    )
    highlighter = editor.markupModel.addRangeHighlighter(
      rangeStart,
      rangeEnd,
      HighlighterLayer.SELECTION + 10,
      attrs,
      HighlighterTargetArea.EXACT_RANGE,
    )
  }

  private fun clearGlow() {
    highlighter?.let {
      if (!editor.isDisposed) {
        try {
          editor.markupModel.removeHighlighter(it)
        } catch (_: Throwable) {
        }
      }
    }
    highlighter = null
  }

  private fun complete(anchor: Int) {
    completeOnce(anchor)
  }

  private fun completeOnce(anchor: Int?) {
    if (!finished.compareAndSet(false, true)) return
    alarm.cancelAllRequests()
    clearGlow()
    val cb = onDone
    onDone = null
    if (anchor != null && !cancelled.get()) {
      cb?.invoke(anchor)
    }
  }

  companion object {
    private const val PULSE_TICKS = 5
    private const val PULSE_DELAY_MS = 45
    private const val SCRAMBLE_DELAY_MS = 55
    private const val ERASE_DELAY_MS = 28
    private const val GLITCH = "░▒▓█✦✧·﹡※╳╱╲◆◇●○"

    private val PULSE_COLORS = arrayOf(
      Color(245, 245, 245), 
      Color(210, 210, 210), 
      Color(170, 170, 170), 
      Color(130, 130, 130), 
      Color(190, 190, 190), 
    )
  }
}
