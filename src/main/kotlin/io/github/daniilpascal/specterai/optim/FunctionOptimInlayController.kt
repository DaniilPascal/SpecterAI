package io.github.daniilpascal.specterai.optim

import com.intellij.ide.DataManager
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorCustomElementRenderer
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.Inlay
import com.intellij.openapi.editor.colors.EditorFontType
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.editor.event.EditorFactoryEvent
import com.intellij.openapi.editor.event.EditorFactoryListener
import com.intellij.openapi.editor.event.EditorMouseEvent
import com.intellij.openapi.editor.event.EditorMouseEventArea
import com.intellij.openapi.editor.event.EditorMouseListener
import com.intellij.openapi.editor.event.EditorMouseMotionListener
import com.intellij.openapi.editor.event.VisibleAreaEvent
import com.intellij.openapi.editor.event.VisibleAreaListener
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.ui.MessageType
import com.intellij.openapi.ui.popup.Balloon
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.Key
import com.intellij.ui.JBColor
import com.intellij.ui.awt.RelativePoint
import com.intellij.util.Alarm
import io.github.daniilpascal.specterai.command.AiCommandRunner
import io.github.daniilpascal.specterai.settings.AiSettings
import io.github.daniilpascal.specterai.swarm.SwarmActivity
import java.awt.Color
import java.awt.Cursor
import java.awt.Font
import java.awt.Graphics
import java.awt.Point
import java.awt.Rectangle
import java.awt.event.MouseEvent


class FunctionOptimInlayController : EditorFactoryListener {

  override fun editorCreated(event: EditorFactoryEvent) {
    attach(event.editor)
  }

  override fun editorReleased(event: EditorFactoryEvent) {
    detach(event.editor)
  }

  companion object {
    private val SESSION_KEY = Key.create<Session>("Specter.FunctionOptimSession")

    fun attach(editor: Editor) {
      if (editor.isViewer) return
      if (editor.getUserData(SESSION_KEY) != null) return
      val project = editor.project ?: return
      if (project.isDisposed) return
      if (!isSupportedFile(editor)) return

      val session = Session(editor)
      editor.putUserData(SESSION_KEY, session)
      session.scheduleRefresh(immediate = true)
    }

    fun detach(editor: Editor) {
      val session = editor.getUserData(SESSION_KEY) ?: return
      editor.putUserData(SESSION_KEY, null)
      Disposer.dispose(session)
    }

    fun attachToOpenEditors() {
      for (editor in EditorFactory.getInstance().allEditors) {
        attach(editor)
      }
    }

    
    fun refreshAllEditors() {
      val app = ApplicationManager.getApplication()
      val run = Runnable {
        for (editor in EditorFactory.getInstance().allEditors) {
          if (editor.isDisposed) continue
          val session = editor.getUserData(SESSION_KEY)
          if (session == null) {
            attach(editor)
          } else {
            session.scheduleRefresh(immediate = true)
          }
        }
      }
      if (app.isDispatchThread) run.run() else app.invokeLater(run)
    }

    private fun isSupportedFile(editor: Editor): Boolean {
      val file = FileDocumentManager.getInstance().getFile(editor.document) ?: return true
      val name = file.name
      val lower = name.lowercase()
      return lower.endsWith(".kt") ||
        lower.endsWith(".kts") ||
        lower.endsWith(".java") ||
        lower.endsWith(".py") ||
        lower.endsWith(".js") ||
        lower.endsWith(".ts") ||
        lower.endsWith(".go") ||
        lower.endsWith(".rs") ||
        lower.endsWith(".c") ||
        lower.endsWith(".cpp") ||
        lower.endsWith(".cs") ||
        lower.endsWith(".txt") ||
        lower.endsWith(".md") ||
        lower.endsWith(".psc")
    }

    private fun colorFor(percent: Int): Color = when {
      percent >= 75 -> JBColor(Color(40, 160, 90), Color(90, 210, 130))
      percent >= 45 -> JBColor(Color(180, 140, 20), Color(230, 190, 70))
      else -> JBColor(Color(190, 70, 60), Color(240, 110, 100))
    }

    private fun formatLabel(
      name: String,
      optim: FunctionOptimScore.Breakdown,
      settings: AiSettings,
    ): String {
      val head = if (settings.optimShowFunctionName) "$name · " else ""
      val base = "  ${head}Optim ${optim.percent}% ▾"
      if (!settings.optimShowFactors || optim.factors.isEmpty()) return base
      
      val factors = FunctionOptimScore.topFactors(optim.factors, limit = 2).joinToString(" ")
      return "$base  $factors"
    }
  }

  private class Session(private val editor: Editor) : Disposable {
    private val alarm = Alarm(Alarm.ThreadToUse.SWING_THREAD, this)
    private val inlays = mutableListOf<Inlay<*>>()
    private val hitsByInlay = HashMap<Inlay<*>, FunctionOptimScanner.FunctionHit>()
    
    private val entriesByLine = HashMap<Int, InlayEntry>()
    private var disposed = false
    private var hoveringBadge = false
    
    private var lastScanStamp = -1L
    
    private var lastHits: List<FunctionOptimScanner.FunctionHit> = emptyList()
    
    private var dirtyStart = -1
    private var dirtyEnd = -1
    
    private var visiblePassOnly = false
    
    private val smoothedPercentByKey = HashMap<String, Int>()

    private data class InlayEntry(
      val inlay: Inlay<*>,
      val hit: FunctionOptimScanner.FunctionHit,
      val label: String,
      val percent: Int,
    )

    private val documentListener = object : DocumentListener {
      override fun documentChanged(event: DocumentEvent) {
        val start = event.offset
        val end = event.offset + maxOf(event.newLength, event.oldLength).coerceAtLeast(1)
        if (dirtyStart < 0) {
          dirtyStart = start
          dirtyEnd = end
        } else {
          dirtyStart = minOf(dirtyStart, start)
          dirtyEnd = maxOf(dirtyEnd, end)
        }
        visiblePassOnly = false
        
        if (SwarmActivity.isActive) {
          scheduleRefresh(immediate = false, delayOverrideMs = 500)
          return
        }
        scheduleRefresh(immediate = false)
      }
    }

    private val visibleAreaListener = VisibleAreaListener { _: VisibleAreaEvent ->
      if (disposed || editor.isDisposed) return@VisibleAreaListener
      if (SwarmActivity.isActive) return@VisibleAreaListener
      if (lastHits.isEmpty()) return@VisibleAreaListener
      visiblePassOnly = true
      scheduleRefresh(immediate = false, delayOverrideMs = 120)
    }

    private val mouseListener = object : EditorMouseListener {
      override fun mouseClicked(event: EditorMouseEvent) {
        if (event.area != EditorMouseEventArea.EDITING_AREA) return
        if (event.mouseEvent.button != MouseEvent.BUTTON1) return
        val hit = resolveHit(event) ?: return
        event.mouseEvent.consume()
        showActionsPopup(event, hit)
      }
    }

    private val motionListener = object : EditorMouseMotionListener {
      override fun mouseMoved(event: EditorMouseEvent) {
        val over = resolveHit(event) != null
        if (over == hoveringBadge) return
        hoveringBadge = over
        editor.contentComponent.cursor =
          if (over) Cursor.getPredefinedCursor(Cursor.HAND_CURSOR) else Cursor.getDefaultCursor()
      }
    }

    init {
      editor.document.addDocumentListener(documentListener, this)
      editor.scrollingModel.addVisibleAreaListener(visibleAreaListener, this)
      editor.addEditorMouseListener(mouseListener, this)
      editor.addEditorMouseMotionListener(motionListener, this)
    }

    fun scheduleRefresh(immediate: Boolean, delayOverrideMs: Int? = null) {
      if (disposed || editor.isDisposed) return
      alarm.cancelAllRequests()
      val delay = when {
        immediate -> 0
        delayOverrideMs != null -> delayOverrideMs
        else -> AiSettings.getInstance().optimRefreshDelayMs
      }
      alarm.addRequest({ refresh() }, delay)
    }

    private fun refresh() {
      if (disposed || editor.isDisposed) return
      val settings = AiSettings.getInstance()
      if (!settings.optimHintsEnabled) {
        clearInlays()
        return
      }
      
      if (SwarmActivity.isActive) {
        scheduleRefresh(immediate = false, delayOverrideMs = 400)
        return
      }

      val doc = editor.document
      val stamp = doc.modificationStamp
      val onlyVisible = visiblePassOnly
      visiblePassOnly = false

      
      if (!onlyVisible || lastHits.isEmpty() || stamp != lastScanStamp) {
        if (stamp != lastScanStamp || lastHits.isEmpty()) {
          lastHits = FunctionOptimScanner.scan(editor.project, doc)
          lastScanStamp = stamp
        }
      }

      val (visFirst, visLast) = visibleLineRange()
      val (dirtyFirst, dirtyLast) = if (onlyVisible) -1 to -1 else consumeDirtyLineRange()

      val wanted = LinkedHashMap<Int, Pair<FunctionOptimScanner.FunctionHit, String>>()
      val liveKeys = HashSet<String>()
      for (hit in lastHits) {
        val optim = hit.optim ?: continue 
        val line = hit.signatureLine
        if (line < 0 || line >= doc.lineCount) continue
        val key = hysteresisKey(hit)
        liveKeys += key
        val shown = FunctionOptimScore.applyHysteresis(smoothedPercentByKey[key], optim.percent)
        smoothedPercentByKey[key] = shown
        val display = optim.copy(percent = shown)
        val displayHit = hit.copy(optim = display)
        val label = formatLabel(hit.name, display, settings)
        wanted[line] = displayHit to label
      }

      
      if (!onlyVisible) {
        val stale = entriesByLine.keys.filter { it !in wanted }.toList()
        for (line in stale) {
          removeEntry(line)
        }
        smoothedPercentByKey.keys.retainAll(liveKeys)
      }

      
      for ((line, pair) in wanted) {
        val inVisible = line in visFirst..visLast
        val inDirty = dirtyFirst >= 0 && line in dirtyFirst..dirtyLast
        val existing = entriesByLine[line]
        if (!inVisible && !inDirty && existing != null) {
          
          hitsByInlay[existing.inlay] = pair.first
          continue
        }
        if (!inVisible && !inDirty && existing == null) {
          
          continue
        }

        val (hit, label) = pair
        val percent = hit.optim?.percent ?: continue
        if (existing != null &&
          existing.inlay.isValid &&
          existing.label == label &&
          existing.percent == percent &&
          existing.hit.name == hit.name
        ) {
          hitsByInlay[existing.inlay] = hit
          entriesByLine[line] = existing.copy(hit = hit)
          continue
        }
        if (existing != null) removeEntry(line)
        val endOffset = doc.getLineEndOffset(line)
        val inlay = editor.inlayModel.addAfterLineEndElement(
          endOffset,
          false,
          OptimLabelRenderer(label, colorFor(percent)),
        ) ?: continue
        val entry = InlayEntry(inlay, hit, label, percent)
        entriesByLine[line] = entry
        hitsByInlay[inlay] = hit
        inlays += inlay
      }
    }

    private fun hysteresisKey(hit: FunctionOptimScanner.FunctionHit): String =
      "${hit.name}@${hit.nameOffset}"

    private fun visibleLineRange(): Pair<Int, Int> {
      return try {
        val area = editor.scrollingModel.visibleArea
        val start = editor.xyToLogicalPosition(java.awt.Point(0, area.y)).line
        val end = editor.xyToLogicalPosition(java.awt.Point(0, area.y + area.height)).line
        val pad = 8
        val first = (start - pad).coerceAtLeast(0)
        val last = (end + pad).coerceAtMost((editor.document.lineCount - 1).coerceAtLeast(0))
        first to last
      } catch (_: Throwable) {
        0 to (editor.document.lineCount - 1).coerceAtLeast(0)
      }
    }

    private fun consumeDirtyLineRange(): Pair<Int, Int> {
      val start = dirtyStart
      val end = dirtyEnd
      dirtyStart = -1
      dirtyEnd = -1
      if (start < 0) return -1 to -1
      return try {
        val doc = editor.document
        val pad = 40 
        val from = (start - pad).coerceAtLeast(0)
        val to = (end + pad).coerceAtMost(doc.textLength)
        val first = doc.getLineNumber(from)
        val last = doc.getLineNumber(to.coerceAtLeast(from))
        first to last
      } catch (_: Throwable) {
        -1 to -1
      }
    }

    private fun resolveHit(event: EditorMouseEvent): FunctionOptimScanner.FunctionHit? {
      event.inlay?.let { hitsByInlay[it] }?.let { return it }
      
      val point = event.mouseEvent.point
      for (candidate in inlays) {
        if (!candidate.isValid) continue
        val bounds = candidate.bounds ?: continue
        if (!bounds.contains(point)) continue
        hitsByInlay[candidate]?.let { return it }
      }
      return null
    }

    private fun showActionsPopup(event: EditorMouseEvent, hit: FunctionOptimScanner.FunctionHit) {
      val project = editor.project ?: return
      val percent = hit.optim?.percent ?: 0
      val group = DefaultActionGroup().apply {
        add(object : AnAction("Explain Optim $percent%") {
          override fun actionPerformed(e: AnActionEvent) {
            showExplainBalloon(hit, event.mouseEvent.point)
          }
        })
        add(object : AnAction("Fix with AI") {
          override fun actionPerformed(e: AnActionEvent) {
            AiCommandRunner.getInstance(project).runOptimFix(editor, hit)
          }
        })
      }
      val dataContext = DataManager.getInstance().getDataContext(editor.contentComponent)
      JBPopupFactory.getInstance()
        .createActionGroupPopup(
          "${hit.name} · Optim $percent%",
          group,
          dataContext,
          JBPopupFactory.ActionSelectionAid.SPEEDSEARCH,
          true,
        )
        .show(RelativePoint(event.mouseEvent))
    }

    private fun showExplainBalloon(hit: FunctionOptimScanner.FunctionHit, point: Point) {
      val html = OptimFactorExplain.buildHtml(hit)
      val balloon = JBPopupFactory.getInstance()
        .createHtmlTextBalloonBuilder(html, MessageType.INFO, null)
        .setAnimationCycle(120)
        .setHideOnClickOutside(true)
        .setHideOnKeyOutside(true)
        .createBalloon()
      balloon.show(RelativePoint(editor.contentComponent, point), Balloon.Position.below)
    }

    private fun removeEntry(line: Int) {
      val entry = entriesByLine.remove(line) ?: return
      hitsByInlay.remove(entry.inlay)
      inlays.remove(entry.inlay)
      if (entry.inlay.isValid) entry.inlay.dispose()
    }

    private fun clearInlays() {
      entriesByLine.keys.toList().forEach { removeEntry(it) }
      entriesByLine.clear()
      inlays.forEach { if (it.isValid) it.dispose() }
      inlays.clear()
      hitsByInlay.clear()
      lastScanStamp = -1L
      lastHits = emptyList()
      dirtyStart = -1
      dirtyEnd = -1
      visiblePassOnly = false
      smoothedPercentByKey.clear()
      if (hoveringBadge && !editor.isDisposed) {
        hoveringBadge = false
        editor.contentComponent.cursor = Cursor.getDefaultCursor()
      }
    }

    override fun dispose() {
      disposed = true
      alarm.cancelAllRequests()
      clearInlays()
    }
  }

  private class OptimLabelRenderer(
    private val text: String,
    private val color: Color,
  ) : EditorCustomElementRenderer {
    override fun calcWidthInPixels(inlay: Inlay<*>): Int {
      val font = inlay.editor.colorsScheme.getFont(EditorFontType.PLAIN)
      val metrics = inlay.editor.contentComponent.getFontMetrics(font)
      return metrics.stringWidth(text) + 8
    }

    override fun paint(
      inlay: Inlay<*>,
      g: Graphics,
      targetRegion: Rectangle,
      textAttributes: TextAttributes,
    ) {
      val editor = inlay.editor
      val font = editor.colorsScheme.getFont(EditorFontType.PLAIN).deriveFont(Font.ITALIC)
      g.font = font
      g.color = color
      val metrics = g.fontMetrics
      val y = targetRegion.y + ((targetRegion.height - metrics.height) / 2) + metrics.ascent
      g.drawString(text, targetRegion.x + 4, y)
    }
  }
}
