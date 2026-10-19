package io.github.daniilpascal.specterai.swarm

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.CaretVisualAttributes
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorCustomElementRenderer
import com.intellij.openapi.editor.Inlay
import com.intellij.openapi.editor.ScrollType
import com.intellij.openapi.editor.markup.CustomHighlighterRenderer
import com.intellij.openapi.editor.markup.HighlighterLayer
import com.intellij.openapi.editor.markup.HighlighterTargetArea
import com.intellij.openapi.editor.markup.RangeHighlighter
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.ui.JBColor
import com.intellij.util.Alarm
import com.intellij.util.DocumentUtil
import io.github.daniilpascal.specterai.ai.TypingSpeed
import java.awt.Color
import java.awt.Font
import java.awt.Graphics
import java.awt.Rectangle
import java.awt.Toolkit
import java.util.concurrent.atomic.AtomicBoolean

class AiSwarmTyper(
  private val project: Project,
  private val editor: Editor,
) {
  private val alarm = Alarm(Alarm.ThreadToUse.SWING_THREAD, project)
  private val highlighters = mutableListOf<RangeHighlighter>()
  private val inlays = mutableListOf<Inlay<*>>()
  private val cancelled = AtomicBoolean(false)
  private var tickCount = 0
  private var previousBlockCursor: Boolean? = null
  private var onDoneCallback: (() -> Unit)? = null
  private var onProgressCallback: ((typed: Int, total: Int) -> Unit)? = null
  private val finished = AtomicBoolean(false)
  private var lastReportedPercent = -1

  init {
    Disposer.register(project) { cancel() }
  }

  val isActive: Boolean
    get() = !finished.get() && !cancelled.get()

  fun start(
    blocks: List<String>,
    insertOffset: Int,
    typingSpeed: Int? = null,
    onDone: () -> Unit = {},
    onProgress: ((typed: Int, total: Int) -> Unit)? = null,
  ) {
    val placements = blocks.map {
      SmartInsertPlanner.Placement(insertOffset, it, SmartInsertPlanner.Slot.NEAR_CARET)
    }
    startPlacements(placements, typingSpeed, onDone, onProgress)
  }

  fun startPlacements(
    placements: List<SmartInsertPlanner.Placement>,
    typingSpeed: Int? = null,
    onDone: () -> Unit = {},
    onProgress: ((typed: Int, total: Int) -> Unit)? = null,
  ) {
    onDoneCallback = onDone
    onProgressCallback = onProgress
    lastReportedPercent = -1
    finished.set(false)
    if (placements.isEmpty()) {
      completeOnce()
      return
    }

    cancelled.set(false)
    tickCount = 0
    val prepared = placements.map { it.copy(block = it.block.trimEnd() + "\n\n") }
    val starts = IntArray(prepared.size) { -1 }

    
    WriteCommandAction.runWriteCommandAction(project) {
      DocumentUtil.executeInBulk(editor.document) {
        val ordered = prepared
          .mapIndexed { index, placement -> index to placement }
          .sortedWith(
            compareByDescending<Pair<Int, SmartInsertPlanner.Placement>> { it.second.offset }
              .thenByDescending { it.first },
          )

        for ((index, placement) in ordered) {
          val placeholder = placement.block.map { ch -> if (ch == '\n') '\n' else ' ' }.joinToString("")
          val at = placement.offset.coerceIn(0, editor.document.textLength)
          editor.document.insertString(at, placeholder)
          
          for (i in starts.indices) {
            if (starts[i] >= 0 && starts[i] >= at) {
              starts[i] += placeholder.length
            }
          }
          starts[index] = at
        }
      }
    }

    val agents = prepared.mapIndexed { index, placement ->
      Agent(
        index = index,
        start = starts[index],
        text = placement.block,
        pos = 0,
        slot = placement.slot,
        label = placement.label,
      )
    }

    previousBlockCursor = editor.settings.isBlockCursor
    editor.settings.isBlockCursor = true

    WriteCommandAction.runWriteCommandAction(project) {
      syncCarets(agents, requestFocus = true)
      editor.scrollingModel.scrollToCaret(ScrollType.MAKE_VISIBLE)
    }

    paintGhosts(agents)
    val profile = TypingSpeed.resolve(typingSpeed)
    
    SwarmActivity.begin()
    tick(agents, profile = profile)
  }

  private fun tick(agents: List<Agent>, profile: TypingSpeed.Profile) {
    if (cancelled.get() || project.isDisposed || editor.isDisposed) {
      finishVisuals()
      completeOnce()
      return
    }

    val active = agents.filter { it.pos < it.text.length }
    if (active.isEmpty()) {
      playFinishPulse(agents)
      return
    }

    tickCount += 1
    var progressed = false
    try {
      WriteCommandAction.runWriteCommandAction(project) {
        DocumentUtil.executeInBulk(editor.document) {
          for (agent in active) {
            if (agent.pos >= agent.text.length) continue
            val want = profile.charsPerTick
            val chunkEnd = (agent.pos + want).coerceAtMost(agent.text.length)
            val at = agent.start + agent.pos
            val len = chunkEnd - agent.pos
            if (at < 0 || at + len > editor.document.textLength) {
              val rest = agent.text.substring(agent.pos)
              val insertAt = editor.document.textLength
              editor.document.insertString(insertAt, rest)
              agent.start = insertAt - agent.pos
              agent.pos = agent.text.length
              progressed = true
              continue
            }
            val chunk = agent.text.substring(agent.pos, chunkEnd)
            editor.document.replaceString(at, at + len, chunk)
            agent.pos = chunkEnd
            progressed = true
          }
        }
        
        
        syncCarets(agents, requestFocus = tickCount == 1)
        editor.scrollingModel.scrollToCaret(ScrollType.MAKE_VISIBLE)
      }
      reportProgress(agents)
    } catch (_: Throwable) {
      finishVisuals()
      completeOnce()
      return
    }

    paintGhosts(agents)

    if (!progressed) {
      finishVisuals()
      completeOnce()
      return
    }

    if (!cancelled.get()) {
      alarm.addRequest({ tick(agents, profile) }, profile.delayMs)
    }
  }

  
  private fun playFinishPulse(agents: List<Agent>) {
    if (cancelled.get() || project.isDisposed || editor.isDisposed) {
      finishVisuals()
      completeOnce()
      return
    }
    try {
      Toolkit.getDefaultToolkit().beep()
    } catch (_: Throwable) {
      
    }
    var frame = 0
    fun step() {
      if (cancelled.get() || project.isDisposed || editor.isDisposed) {
        finishVisuals()
        completeOnce()
        return
      }
      if (frame >= FINISH_PULSE_FRAMES) {
        finishVisuals()
        completeOnce()
        return
      }
      paintFinishPulse(agents, frame)
      frame += 1
      alarm.addRequest({ step() }, FINISH_PULSE_DELAY_MS)
    }
    step()
  }

  private fun paintFinishPulse(agents: List<Agent>, frame: Int) {
    if (editor.isDisposed) return
    clearGhosts()
    val markup = editor.markupModel
    val docLen = editor.document.textLength
    
    val t = if (FINISH_PULSE_FRAMES <= 1) 1f
    else {
      val mid = (FINISH_PULSE_FRAMES - 1) / 2f
      1f - (kotlin.math.abs(frame - mid) / mid.coerceAtLeast(0.5f))
    }
    val width = (CARET_WIDTH + (FINISH_PULSE_EXTRA_WIDTH * t).toInt()).coerceAtLeast(CARET_WIDTH)
    val glowAlpha = (90 + (130 * t).toInt()).coerceIn(90, 220)

    for (agent in agents) {
      if (agent.text.isEmpty()) continue
      val end = (agent.start + agent.text.length).coerceIn(0, docLen)
      val color = brighten(accentFor(agent.slot), t)
      paintCaretBar(end.coerceAtMost(docLen), color, width)

      
      val glowStart = agent.start.coerceIn(0, docLen)
      val glowEnd = end.coerceIn(glowStart, docLen)
      if (glowStart < glowEnd) {
        val bg = withAlpha(accentFor(agent.slot), glowAlpha)
        highlighters += markup.addRangeHighlighter(
          glowStart,
          glowEnd,
          HighlighterLayer.SELECTION - 1,
          TextAttributes(
            JBColor(color, color),
            JBColor(bg, bg),
            null,
            null,
            Font.BOLD,
          ),
          HighlighterTargetArea.EXACT_RANGE,
        )
      }
    }

    
    if (!project.isDisposed && !editor.isDisposed) {
      try {
        WriteCommandAction.runWriteCommandAction(project) {
          val done = agents.filter { it.text.isNotEmpty() }
          if (done.isEmpty()) return@runWriteCommandAction
          editor.caretModel.removeSecondaryCarets()
          val lead = done.first()
          val leadAt = (lead.start + lead.text.length).coerceIn(0, editor.document.textLength)
          editor.caretModel.moveToOffset(leadAt)
          editor.caretModel.primaryCaret.visualAttributes =
            CaretVisualAttributes(
              brighten(accentFor(lead.slot), t),
              CaretVisualAttributes.Weight.HEAVY,
              CaretVisualAttributes.Shape.BLOCK,
              2.0f + t,
            )
          for (agent in done.drop(1)) {
            val at = (agent.start + agent.text.length).coerceIn(0, editor.document.textLength)
            val logical = editor.offsetToLogicalPosition(at)
            val caret = editor.caretModel.addCaret(logical, true)
            caret?.visualAttributes = CaretVisualAttributes(
              brighten(accentFor(agent.slot), t),
              CaretVisualAttributes.Weight.HEAVY,
              CaretVisualAttributes.Shape.BLOCK,
              2.0f + t,
            )
          }
        }
      } catch (_: Throwable) {
        
      }
    }
  }

  private fun finishVisuals() {
    alarm.cancelAllRequests()
    clearGhosts()
    previousBlockCursor?.let { editor.settings.isBlockCursor = it }
    previousBlockCursor = null
    if (!project.isDisposed && !editor.isDisposed) {
      WriteCommandAction.runWriteCommandAction(project) {
        editor.caretModel.allCarets.forEach {
          it.visualAttributes = defaultCaretAttrs()
        }
        editor.caretModel.removeSecondaryCarets()
      }
    }
  }

  private fun reportProgress(agents: List<Agent>) {
    val cb = onProgressCallback ?: return
    val total = agents.sumOf { it.text.length }.coerceAtLeast(1)
    val typed = agents.sumOf { it.pos.coerceAtMost(it.text.length) }
    val percent = ((typed * 100L) / total).toInt()
    if (percent == lastReportedPercent) return
    lastReportedPercent = percent
    cb(typed, total)
  }

  private fun completeOnce() {
    if (!finished.compareAndSet(false, true)) return
    SwarmActivity.end()
    onProgressCallback = null
    val cb = onDoneCallback
    onDoneCallback = null
    cb?.invoke()
  }

  private fun syncCarets(agents: List<Agent>, requestFocus: Boolean = false) {
    val stillTyping = agents.filter { it.pos < it.text.length }
    if (stillTyping.isEmpty()) return

    editor.caretModel.removeSecondaryCarets()
    val lead = stillTyping.first()
    val leadAt = (lead.start + lead.pos).coerceIn(0, editor.document.textLength)
    editor.caretModel.moveToOffset(leadAt)
    val primary = editor.caretModel.primaryCaret
    primary.visualAttributes = caretAttrs(lead.slot)
    if (requestFocus) {
      editor.contentComponent.requestFocusInWindow()
    }

    for (agent in stillTyping.drop(1)) {
      val at = (agent.start + agent.pos).coerceIn(0, editor.document.textLength)
      
      val logical = editor.offsetToLogicalPosition(at)
      val caret = editor.caretModel.addCaret(logical, true)
      if (caret != null) {
        caret.visualAttributes = caretAttrs(agent.slot)
      }
    }
  }

  private fun paintGhosts(agents: List<Agent>) {
    if (editor.isDisposed) return
    clearGhosts()
    val markup = editor.markupModel
    val docLen = editor.document.textLength
    for (agent in agents) {
      if (agent.pos >= agent.text.length) continue
      val at = (agent.start + agent.pos).coerceIn(0, docLen)
      val color = accentFor(agent.slot)

      
      paintCaretBar(at, color, CARET_WIDTH)

      
      val remain = (agent.text.length - agent.pos).coerceAtLeast(1)
      val end = (at + remain).coerceAtMost(docLen)
      if (at < end) {
        highlighters += markup.addRangeHighlighter(
          at,
          end,
          HighlighterLayer.SELECTION - 1,
          trailAttributes(agent.slot),
          HighlighterTargetArea.EXACT_RANGE,
        )
      }
    }
  }

  private fun paintCaretBar(offset: Int, color: Color, width: Int = CARET_WIDTH) {
    val docLen = editor.document.textLength
    val at = offset.coerceIn(0, docLen)
    val barWidth = width.coerceAtLeast(1)

    
    val inlay = editor.inlayModel.addInlineElement(
      at,
      false,
      object : EditorCustomElementRenderer {
        override fun calcWidthInPixels(inlay: Inlay<*>): Int = barWidth

        override fun paint(
          inlay: Inlay<*>,
          g: Graphics,
          targetRegion: Rectangle,
          textAttributes: TextAttributes,
        ) {
          g.color = color
          g.fillRect(targetRegion.x, targetRegion.y, barWidth, targetRegion.height)
        }
      },
    )
    if (inlay != null) {
      inlays += inlay
    }

    
    if (docLen > 0) {
      val from = if (at < docLen) at else docLen - 1
      val to = (from + 1).coerceAtMost(docLen)
      val hl = editor.markupModel.addRangeHighlighter(
        from,
        to,
        HighlighterLayer.SELECTION + 1,
        TextAttributes(null, null, null, null, Font.PLAIN),
        HighlighterTargetArea.EXACT_RANGE,
      )
      val drawAtEof = at >= docLen
      hl.customRenderer = CustomHighlighterRenderer { ed, highlighter, g ->
        val start = highlighter.startOffset.coerceIn(0, ed.document.textLength)
        val p = ed.offsetToXY(start)
        val x = if (drawAtEof) {
          try {
            ed.offsetToXY(ed.document.textLength).x
          } catch (_: Throwable) {
            p.x + ed.lineHeight / 3
          }
        } else {
          p.x
        }
        g.color = color
        g.fillRect(x, p.y, barWidth, ed.lineHeight)
      }
      highlighters += hl
    }
  }

  private fun caretAttrs(slot: SmartInsertPlanner.Slot): CaretVisualAttributes =
    CaretVisualAttributes(
      accentFor(slot),
      CaretVisualAttributes.Weight.HEAVY,
      CaretVisualAttributes.Shape.BLOCK,
      2.0f,
    )

  private fun defaultCaretAttrs(): CaretVisualAttributes =
    CaretVisualAttributes(null, CaretVisualAttributes.Weight.NORMAL)

  private fun accentFor(slot: SmartInsertPlanner.Slot): Color = when (slot) {
    SmartInsertPlanner.Slot.IMPORTS -> Color(200, 120, 255)
    SmartInsertPlanner.Slot.NEAR_CARET -> Color(0, 220, 255)
    SmartInsertPlanner.Slot.CLASS_END -> Color(80, 255, 160)
    SmartInsertPlanner.Slot.FILE_END -> Color(255, 200, 60)
  }

  private fun trailAttributes(slot: SmartInsertPlanner.Slot): TextAttributes {
    
    val bg = when (slot) {
      SmartInsertPlanner.Slot.IMPORTS -> Color(110, 50, 170, 150)
      SmartInsertPlanner.Slot.NEAR_CARET -> Color(15, 130, 190, 160)
      SmartInsertPlanner.Slot.CLASS_END -> Color(25, 140, 85, 150)
      SmartInsertPlanner.Slot.FILE_END -> Color(170, 110, 25, 150)
    }
    val fg = accentFor(slot)
    return TextAttributes(
      JBColor(fg, fg),
      JBColor(bg, bg),
      null,
      null,
      Font.BOLD,
    )
  }

  private fun brighten(base: Color, t: Float): Color {
    val lift = (80 * t).toInt().coerceIn(0, 80)
    return Color(
      (base.red + lift).coerceAtMost(255),
      (base.green + lift).coerceAtMost(255),
      (base.blue + lift).coerceAtMost(255),
    )
  }

  private fun withAlpha(base: Color, alpha: Int): Color =
    Color(base.red, base.green, base.blue, alpha.coerceIn(0, 255))

  private fun clearGhosts() {
    if (!editor.isDisposed) {
      val markup = editor.markupModel
      highlighters.forEach { markup.removeHighlighter(it) }
      inlays.forEach { it.dispose() }
    }
    highlighters.clear()
    inlays.clear()
  }

  fun cancel() {
    if (finished.get()) return
    cancelled.set(true)
    alarm.cancelAllRequests()
    val runCleanup = {
      finishVisuals()
      completeOnce()
    }
    if (ApplicationManager.getApplication().isDispatchThread) {
      runCleanup()
    } else {
      ApplicationManager.getApplication().invokeLater(runCleanup)
    }
  }

  private class Agent(
    val index: Int,
    var start: Int,
    val text: String,
    var pos: Int,
    val slot: SmartInsertPlanner.Slot,
    val label: String,
  )

  companion object {
    private const val CARET_WIDTH = 4
    private const val FINISH_PULSE_FRAMES = 5
    private const val FINISH_PULSE_DELAY_MS = 45
    private const val FINISH_PULSE_EXTRA_WIDTH = 7
  }
}
