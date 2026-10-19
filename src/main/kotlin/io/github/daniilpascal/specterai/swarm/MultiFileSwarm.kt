package io.github.daniilpascal.specterai.swarm

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.TextEditor
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import io.github.daniilpascal.specterai.ai.AiCodeTemplates
import io.github.daniilpascal.specterai.ai.TargetLanguage
import io.github.daniilpascal.specterai.settings.AiSettings
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicIntegerArray


class MultiFileSwarm(private val project: Project) {

  private val typers = CopyOnWriteArrayList<AiSwarmTyper>()
  private val cancelled = AtomicBoolean(false)
  private val finished = AtomicBoolean(false)
  private var onDoneCallback: (() -> Unit)? = null
  private var onProgressCallback: ((percent: Int) -> Unit)? = null
  private var startedAtMs = 0L
  private var startedBlocks = 0
  private var startedFiles = 0

  val isActive: Boolean
    get() = !finished.get() && !cancelled.get()

  fun cancel() {
    if (finished.get()) return
    cancelled.set(true)
    typers.toList().forEach { it.cancel() }
    if (typers.isEmpty()) completeOnce()
  }

  
  fun start(
    primaryEditor: Editor,
    resolved: MultiFilePlanner.ResolvedPlan,
    primaryAnchor: Int,
    typingSpeed: Int?,
    forceNearSlot: Boolean,
    onDone: () -> Unit,
    onProgress: ((percent: Int) -> Unit)? = null,
  ): String {
    onDoneCallback = onDone
    onProgressCallback = onProgress
    finished.set(false)
    cancelled.set(false)
    typers.clear()

    val jobs = buildJobs(primaryEditor, resolved, primaryAnchor, forceNearSlot)
    if (jobs.isEmpty()) {
      
      onDoneCallback = null
      onProgressCallback = null
      finished.set(true)
      return "empty"
    }

    startedAtMs = System.currentTimeMillis()
    startedBlocks = jobs.sumOf { it.placements.size }
    startedFiles = jobs.size

    val remaining = AtomicInteger(jobs.size)
    val labels = mutableListOf<String>()
    val typedByJob = AtomicIntegerArray(jobs.size)
    val totalByJob = IntArray(jobs.size) { idx ->
      jobs[idx].placements.sumOf { it.block.length }.coerceAtLeast(0)
    }
    val totalAll = totalByJob.sum().coerceAtLeast(1)
    val lastPercent = AtomicInteger(-1)

    fun publishProgress() {
      var typed = 0
      for (i in 0 until typedByJob.length()) typed += typedByJob.get(i)
      val pct = ((typed.toLong() * 100L) / totalAll).toInt().coerceIn(0, 100)
      if (lastPercent.getAndSet(pct) == pct) return
      onProgressCallback?.invoke(pct)
    }

    jobs.forEachIndexed { index, job ->
      if (cancelled.get()) return@forEachIndexed
      labels += "${job.file.name}×${job.placements.size}"
      val typer = AiSwarmTyper(project, job.editor)
      typers += typer
      typer.startPlacements(
        placements = job.placements,
        typingSpeed = typingSpeed,
        onDone = {
          typedByJob.set(index, totalByJob[index])
          publishProgress()
          typers.remove(typer)
          if (remaining.decrementAndGet() <= 0) {
            completeOnce()
          }
        },
        onProgress = { typed, _ ->
          typedByJob.set(index, typed)
          publishProgress()
        },
      )
    }

    if (typers.isEmpty()) {
      completeOnce()
      return "empty"
    }
    return labels.joinToString(" · ")
  }

  private data class Job(
    val file: VirtualFile,
    val editor: Editor,
    val placements: List<SmartInsertPlanner.Placement>,
  )

  private fun buildJobs(
    primaryEditor: Editor,
    resolved: MultiFilePlanner.ResolvedPlan,
    primaryAnchor: Int,
    forceNearSlot: Boolean,
  ): List<Job> {
    val currentVf = primaryEditor.virtualFile ?: resolved.currentFile

    
    val buckets = LinkedHashMap<String, MutableList<AiCodeTemplates.SemanticBlock>>()

    if (forceNearSlot) {
      val blocks = resolved.sameFileBlocks.ifEmpty {
        resolved.targets.filter { it.isUsable && !it.isCrossFileTarget(resolved) }.map { it.block }
      }
      if (blocks.isEmpty()) return emptyList()
      buckets["current"] = blocks.map { it.copy(targetPath = null) }.toMutableList()
    } else {
      for (t in resolved.targets) {
        if (!t.isUsable) continue
        when {
          t.isCurrentFile || (currentVf != null && t.virtualFile == currentVf) ||
            isSameName(currentVf, t) -> {
            buckets.getOrPut("current") { mutableListOf() } += t.block.copy(targetPath = null)
          }
          t.relativePath != null -> {
            buckets.getOrPut(t.relativePath) { mutableListOf() } += t.block.copy(targetPath = null)
          }
        }
      }
    }

    val jobs = mutableListOf<Job>()
    for ((key, blocks) in buckets) {
      if (blocks.isEmpty()) continue
      val vf: VirtualFile
      val anchor: Int
      val focus: Boolean
      if (key == "current") {
        vf = currentVf ?: continue
        anchor = primaryAnchor.coerceIn(0, primaryEditor.document.textLength)
        focus = true
      } else {
        vf = MultiFilePlanner.ensureFile(project, currentVf, key) ?: continue
        focus = false
        anchor = -1 
      }

      val editor = if (key == "current") {
        primaryEditor
      } else {
        editorFor(vf, focus = focus) ?: continue
      }

      val at = if (key == "current") {
        anchor
      } else {
        if (editor.document.textLength == 0) 0 else editor.document.textLength
      }

      val lang = TargetLanguage.detect(vf.name, editor.document.text)
      val placements = if (forceNearSlot && key == "current") {
        blocks.map {
          SmartInsertPlanner.Placement(
            offset = at,
            block = it.code.trimEnd() + "\n",
            slot = SmartInsertPlanner.Slot.NEAR_CARET,
            label = it.label,
          )
        }
      } else {
        PsiInsertPlanner.plan(project, editor.document, at, blocks, lang)
      }
      if (placements.isEmpty()) continue
      jobs += Job(vf, editor, placements)
    }
    return jobs
  }

  private fun MultiFilePlanner.Target.isCrossFileTarget(plan: MultiFilePlanner.ResolvedPlan): Boolean {
    if (relativePath == null) return false
    val cur = plan.currentFile ?: return true
    if (virtualFile == cur) return false
    return !isSameName(cur, this)
  }

  private fun isSameName(current: VirtualFile?, t: MultiFilePlanner.Target): Boolean {
    if (current == null) return false
    val rel = t.relativePath ?: return false
    return rel.equals(current.name, ignoreCase = true)
  }

  private fun editorFor(file: VirtualFile, focus: Boolean): Editor? {
    val fem = FileEditorManager.getInstance(project)
    fem.getEditors(file).filterIsInstance<TextEditor>().firstOrNull()?.editor?.let { return it }
    val opened = fem.openFile(file, focus)
    return opened.filterIsInstance<TextEditor>().firstOrNull()?.editor
      ?: fem.getEditors(file).filterIsInstance<TextEditor>().firstOrNull()?.editor
  }

  private fun completeOnce() {
    if (!finished.compareAndSet(false, true)) return
    if (!cancelled.get() && startedAtMs > 0L) {
      val ms = (System.currentTimeMillis() - startedAtMs).coerceAtLeast(0)
      try {
        AiSettings.getInstance().recordSwarm(ms, startedBlocks, startedFiles)
      } catch (_: Throwable) {
      }
    }
    onProgressCallback = null
    val cb = onDoneCallback
    onDoneCallback = null
    val run = Runnable { cb?.invoke() }
    if (ApplicationManager.getApplication().isDispatchThread) run.run()
    else ApplicationManager.getApplication().invokeLater(run)
  }
}
