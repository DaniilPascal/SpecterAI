package io.github.daniilpascal.specterai.highlight

import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer
import com.intellij.codeInsight.daemon.impl.HighlightInfo
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.Disposable
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.editor.event.EditorFactoryEvent
import com.intellij.openapi.editor.event.EditorFactoryListener
import com.intellij.openapi.editor.markup.EffectType
import com.intellij.openapi.editor.markup.HighlighterLayer
import com.intellij.openapi.editor.markup.RangeHighlighter
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.Key
import com.intellij.util.Alarm
import io.github.daniilpascal.specterai.command.AiCommandSyntax
import io.github.daniilpascal.specterai.settings.AiSettings
import io.github.daniilpascal.specterai.swarm.SwarmActivity


class AiCommandSyntaxHighlighter : EditorFactoryListener {

  override fun editorCreated(event: EditorFactoryEvent) {
    attach(event.editor)
  }

  override fun editorReleased(event: EditorFactoryEvent) {
    detach(event.editor)
  }

  companion object {
    private val SESSION_KEY = Key.create<Session>("Specter.AiCommandSyntaxSession")
    val OUR_COLOR_KEY = Key.create<Boolean>("Specter.AiCommandColorHL")

    fun attach(editor: Editor) {
      if (editor.isViewer) return
      if (editor.getUserData(SESSION_KEY) != null) return
      val project = editor.project ?: return
      if (project.isDisposed) return
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
          if (session == null) attach(editor) else session.scheduleRefresh(immediate = true)
        }
      }
      if (app.isDispatchThread) run.run() else app.invokeLater(run)
    }
  }

  private class Session(private val editor: Editor) : Disposable {
    private val alarm = Alarm(Alarm.ThreadToUse.SWING_THREAD, this)
    private var disposed = false
    private var lastStripStamp = -1L

    private val documentListener = object : DocumentListener {
      override fun documentChanged(event: DocumentEvent) {
        
        if (SwarmActivity.isActive) return
        scheduleRefresh(immediate = false)
      }
    }

    private val daemonListener = object : DaemonCodeAnalyzer.DaemonListener {
      override fun daemonFinished() {
        if (SwarmActivity.isActive) return
        
        scheduleStripProblems(120)
      }
    }

    init {
      editor.document.addDocumentListener(documentListener, this)
      editor.project?.messageBus?.connect(this)?.subscribe(
        DaemonCodeAnalyzer.DAEMON_EVENT_TOPIC,
        daemonListener,
      )
    }

    fun scheduleRefresh(immediate: Boolean) {
      if (disposed || editor.isDisposed) return
      if (SwarmActivity.isActive && !immediate) return
      alarm.cancelAllRequests()
      alarm.addRequest(
        { stripProblemHighlighters() },
        if (immediate) 0 else 80,
      )
    }

    private fun scheduleStripProblems(delayMs: Int) {
      if (disposed || editor.isDisposed) return
      alarm.addRequest({ stripProblemHighlighters() }, delayMs)
    }

    private fun stripProblemHighlighters() {
      if (disposed || editor.isDisposed) return
      if (!AiSettings.getInstance().enabled) return
      if (SwarmActivity.isActive) return

      val stamp = editor.document.modificationStamp
      if (stamp == lastStripStamp) return
      lastStripStamp = stamp

      val matches = AiCommandSyntax.highlightMatchesFor(editor.document)
      if (matches.isEmpty()) return

      val markup = editor.markupModel
      val toRemove = mutableListOf<RangeHighlighter>()
      for (hl in markup.allHighlighters) {
        if (!hl.isValid) continue
        if (hl.getUserData(OUR_COLOR_KEY) == true) continue
        val hs = hl.startOffset
        val he = hl.endOffset
        val overlaps = matches.any { hs < it.end && he > it.start }
        if (!overlaps) continue
        if (shouldStrip(hl)) {
          toRemove += hl
        }
      }
      for (hl in toRemove) {
        try {
          markup.removeHighlighter(hl)
        } catch (_: Throwable) {
        }
      }
    }

    private fun shouldStrip(hl: RangeHighlighter): Boolean {
      
      if (hl.layer == HighlighterLayer.SELECTION) return false
      if (hl.layer == HighlighterLayer.CARET_ROW) return false

      
      val info = try {
        HighlightInfo.fromRangeHighlighter(hl)
      } catch (_: Throwable) {
        null
      }
      if (info != null) return true

      
      try {
        if (hl.getErrorStripeMarkColor(editor.colorsScheme) != null) return true
      } catch (_: Throwable) {
      }

      
      val keyName = try {
        hl.textAttributesKey?.externalName?.uppercase().orEmpty()
      } catch (_: Throwable) {
        ""
      }
      if (keyName.contains("ERROR") ||
        keyName.contains("WARNING") ||
        keyName.contains("UNUSED") ||
        keyName.contains("UNRESOLVED") ||
        keyName.contains("DEPRECATED") ||
        keyName.contains("WRONG") ||
        keyName.contains("PROBLEM")
      ) {
        return true
      }

      if (hl.layer >= HighlighterLayer.WEAK_WARNING) return true

      val attrs = try {
        hl.getTextAttributes(editor.colorsScheme)
      } catch (_: Throwable) {
        null
      } ?: return false

      val effect = attrs.effectType ?: return false
      return effect == EffectType.WAVE_UNDERSCORE ||
        effect == EffectType.BOLD_DOTTED_LINE ||
        effect == EffectType.BOXED ||
        effect == EffectType.STRIKEOUT
    }

    override fun dispose() {
      disposed = true
      alarm.cancelAllRequests()
    }
  }
}
