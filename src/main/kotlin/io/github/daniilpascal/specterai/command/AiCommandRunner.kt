package io.github.daniilpascal.specterai.command

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentLinkedQueue
import io.github.daniilpascal.specterai.ai.AiClient
import io.github.daniilpascal.specterai.ai.AiCodeTemplates
import io.github.daniilpascal.specterai.ai.AiModels
import io.github.daniilpascal.specterai.ai.RequestScale
import io.github.daniilpascal.specterai.ai.TargetLanguage
import io.github.daniilpascal.specterai.ai.TypingSpeed
import io.github.daniilpascal.specterai.optim.FunctionOptimScanner
import io.github.daniilpascal.specterai.progress.AiProgress
import io.github.daniilpascal.specterai.settings.AiSettings
import io.github.daniilpascal.specterai.swarm.AiSwarmTyper
import io.github.daniilpascal.specterai.swarm.MultiFilePlanner
import io.github.daniilpascal.specterai.swarm.MultiFileSwarm
import io.github.daniilpascal.specterai.swarm.SmartInsertPlanner

@Service(Service.Level.PROJECT)
class AiCommandRunner(private val project: Project) {

  private val running = AtomicBoolean(false)
  private val cancelRequested = AtomicBoolean(false)
  private val activeSwarm = AtomicReference<MultiFileSwarm?>(null)
  private val activeCeoTyper = AtomicReference<AiSwarmTyper?>(null)
  private val activeHttp = AtomicReference<CompletableFuture<*>?>(null)
  private val activeVanish = AtomicReference<CommandVanishAnimator?>(null)
  private val queue = ConcurrentLinkedQueue<PendingJob>()

  val isBusy: Boolean
    get() = running.get() || queue.isNotEmpty()

  val queuedCount: Int
    get() = queue.size

  private val progress: AiProgress
    get() = AiProgress.getInstance(project)

  
  fun cancel(reason: String = "Отменено"): Boolean {
    val dropped = drainQueue()
    val hadSwarm = activeSwarm.get() != null
    val hadCeo = activeCeoTyper.get() != null
    val hadHttp = activeHttp.get() != null
    val hadVanish = activeVanish.get() != null
    val hadRunning = running.get()
    val hadActive = hadRunning || hadSwarm || hadCeo || hadHttp || hadVanish
    if (!hadActive && dropped == 0) return false

    cancelRequested.set(true)
    activeHttp.getAndSet(null)?.cancel(true)
    activeVanish.getAndSet(null)?.cancel()
    activeCeoTyper.getAndSet(null)?.cancel()
    val swarm = activeSwarm.getAndSet(null)
    if (swarm != null) {
      swarm.cancel()
    } else if (hadRunning && !hadCeo) {
      releaseBusy(pump = false)
    } else if (hadCeo && swarm == null) {
      releaseBusy(pump = false)
    }
    progress.clear()
    val suffix = if (dropped > 0) " · очередь сброшена ($dropped)" else ""
    notify("Specter.AI", reason + suffix, NotificationType.WARNING)
    return true
  }

  fun tryHandle(editor: Editor): Boolean {
    if (tryHandleCeo(editor)) return true

    val match = findCommand(editor) ?: return false
    val job = snapshotCommand(editor, match)

    if (!beginBusy()) {
      return enqueue(job)
    }
    startCommandJob(job)
    return true
  }

  
  private fun tryHandleCeo(editor: Editor): Boolean {
    val match = AiCeoSyntax.findCompleteNearCaret(editor.document.text, editor.caretModel.offset)
      ?: return false
    if (!beginBusy()) return false

    val docLen = editor.document.textLength
    val start = match.start.coerceIn(0, docLen)
    val end = match.end.coerceIn(start, docLen)
    if (start >= end) {
      releaseBusy(pump = false)
      return false
    }

    val fileName = editor.virtualFile?.name ?: "Scratch.kt"
    val language = TargetLanguage.detect(fileName, editor.document.text)
    val commentLine = ceoCommentLine(language)

    val vanish = CommandVanishAnimator(project, editor)
    activeVanish.set(vanish)
    vanish.start(start, end) { _ ->
      activeVanish.compareAndSet(vanish, null)
      if (project.isDisposed || cancelRequested.get() || editor.isDisposed) {
        releaseBusy(pump = !cancelRequested.get())
        return@start
      }

      WriteCommandAction.runWriteCommandAction(project, "Specter.AI.CEO", "SpecterAI", {
        if (editor.isDisposed) return@runWriteCommandAction
        val doc = editor.document
        trimEmptyCommandLine(doc, start.coerceIn(0, doc.textLength))
        val text = doc.text
        if (text.isNotEmpty() && !text.endsWith("\n")) {
          doc.insertString(doc.textLength, "\n")
        }
      })

      if (project.isDisposed || cancelRequested.get() || editor.isDisposed) {
        releaseBusy(pump = !cancelRequested.get())
        return@start
      }

      val typer = AiSwarmTyper(project, editor)
      activeCeoTyper.set(typer)
      val offset = editor.document.textLength
      typer.start(
        blocks = listOf(commentLine),
        insertOffset = offset,
        typingSpeed = 28,
        onDone = {
          activeCeoTyper.compareAndSet(typer, null)
          val cancelled = cancelRequested.get()
          if (!cancelled) {
            notify("Specter.AI", "CEO · Daniil_Pascal", NotificationType.INFORMATION)
          }
          releaseBusy(pump = !cancelled)
        },
      )
    }
    return true
  }

  private fun trimEmptyCommandLine(doc: com.intellij.openapi.editor.Document, at: Int) {
    val text = doc.text
    if (text.isEmpty()) return
    val offset = at.coerceIn(0, text.length)
    val lineStart = text.lastIndexOf('\n', (offset - 1).coerceAtLeast(0)).let { if (it < 0) 0 else it + 1 }
    val lineEnd = text.indexOf('\n', offset).let { if (it < 0) text.length else it }
    val line = text.substring(lineStart, lineEnd)
    if (line.isBlank()) {
      val deleteTo = if (lineEnd < text.length) lineEnd + 1 else lineEnd
      if (deleteTo > lineStart) doc.deleteString(lineStart, deleteTo)
    }
  }

  private fun ceoCommentLine(language: TargetLanguage): String {
    val body = AiCeoSyntax.SIGNATURE
    return when (language) {
      TargetLanguage.PYTHON, TargetLanguage.SHELL, TargetLanguage.RUBY, TargetLanguage.YAML ->
        "# $body"
      TargetLanguage.SQL -> "-- $body"
      TargetLanguage.HTML, TargetLanguage.XML -> "<!-- $body -->"
      TargetLanguage.CSS -> "/* $body */"
      else -> "// $body"
    }
  }

  
  fun runOptimFix(editor: Editor, hit: FunctionOptimScanner.FunctionHit): Boolean {
    val optim = hit.optim ?: return false

    val fileName = editor.virtualFile?.name ?: "Scratch.kt"
    val language = TargetLanguage.detect(fileName, editor.document.text)
    val factors = optim.factors.joinToString(", ").ifBlank { "general cleanup" }
    val prompt = buildString {
      append("Optimize function `").append(hit.name).append("` for performance and code quality. ")
      append("Current Optim ").append(optim.percent).append("%. ")
      append("Address these factors: ").append(factors).append(". ")
      append("Keep the same signature and public behavior. ")
      append("Return ONE block tagged ### near with the FULL replacement function (signature + body), ")
      append("not only the body and not a surrounding class.")
    }
    val context = buildString {
      appendLine("File: $fileName")
      appendLine("Function to replace (offsets ${hit.rangeStart}-${hit.rangeEnd}):")
      append(hit.fullText)
    }
    val job = PendingJob(
      editor = editor,
      prompt = prompt,
      context = context,
      language = language,
      typingSpeed = null,
      start = hit.rangeStart,
      end = hit.rangeEnd,
      replaceRange = hit.rangeStart to hit.rangeEnd,
      forceNearSlot = true,
      label = "Optim Fix · ${hit.name}",
      vanishCommand = false,
    )

    if (!beginBusy()) {
      return enqueue(job)
    }

    notify(
      "Specter.AI",
      "Optim Fix · ${hit.name} · ${optim.percent}% → AI…  (Esc — отмена)",
      NotificationType.INFORMATION,
    )
    startOptimJob(job)
    return true
  }

  private fun snapshotCommand(editor: Editor, match: CommandMatch): PendingJob {
    val fileName = editor.virtualFile?.name ?: "Scratch.kt"
    return PendingJob(
      editor = editor,
      prompt = match.prompt,
      context = extractContext(editor, match.start, match.end, fileName),
      language = TargetLanguage.detect(fileName, editor.document.text),
      typingSpeed = match.typingSpeed,
      start = match.start,
      end = match.end,
      label = previewPrompt(match.prompt),
      vanishCommand = true,
    )
  }

  private fun enqueue(job: PendingJob): Boolean {
    if (queue.size >= MAX_QUEUE) {
      notify(
        "Specter.AI",
        "Очередь полна ($MAX_QUEUE). Дождись или Esc — сброс.",
        NotificationType.WARNING,
      )
      return false
    }
    queue.offer(job)
    val n = queue.size
    notify(
      "Specter.AI",
      "В очереди ($n): ${job.label}",
      NotificationType.INFORMATION,
    )
    return true
  }

  private fun startCommandJob(job: PendingJob) {
    if (job.editor.isDisposed || project.isDisposed) {
      releaseBusy()
      return
    }
    val docLen = job.editor.document.textLength
    val start = job.start.coerceIn(0, docLen)
    val end = job.end.coerceIn(start, docLen)

    if (!job.vanishCommand || start >= end) {
      launchGeneration(
        editor = job.editor,
        prompt = job.prompt,
        context = job.context,
        language = job.language,
        anchor = start,
        typingSpeed = job.typingSpeed,
        replaceRange = job.replaceRange,
        forceNearSlot = job.forceNearSlot,
      )
      return
    }

    val vanish = CommandVanishAnimator(project, job.editor)
    activeVanish.set(vanish)
    vanish.start(start, end) { anchor ->
      activeVanish.compareAndSet(vanish, null)
      if (project.isDisposed || cancelRequested.get() || job.editor.isDisposed) {
        releaseBusy(pump = !cancelRequested.get())
        return@start
      }
      launchGeneration(
        editor = job.editor,
        prompt = job.prompt,
        context = job.context,
        language = job.language,
        anchor = anchor.coerceIn(0, job.editor.document.textLength),
        typingSpeed = job.typingSpeed,
        replaceRange = job.replaceRange,
        forceNearSlot = job.forceNearSlot,
      )
    }
  }

  private fun startOptimJob(job: PendingJob) {
    if (job.editor.isDisposed || project.isDisposed) {
      releaseBusy()
      return
    }
    val range = job.replaceRange
    launchGeneration(
      editor = job.editor,
      prompt = job.prompt,
      context = job.context,
      language = job.language,
      anchor = (range?.first ?: job.start).coerceIn(0, job.editor.document.textLength),
      typingSpeed = job.typingSpeed,
      replaceRange = range,
      forceNearSlot = job.forceNearSlot,
    )
  }

  private fun startPending(job: PendingJob) {
    if (job.vanishCommand) startCommandJob(job) else startOptimJob(job)
  }

  private fun pumpQueue() {
    if (project.isDisposed || cancelRequested.get()) return
    val next = queue.poll() ?: return
    if (!beginBusy()) {
      
      val rest = ArrayList<PendingJob>(queue.size + 1)
      rest += next
      while (true) {
        val j = queue.poll() ?: break
        rest += j
      }
      rest.forEach { queue.offer(it) }
      return
    }
    val left = queue.size
    if (left > 0) {
      notify(
        "Specter.AI",
        "Следующая из очереди ($left ещё): ${next.label}",
        NotificationType.INFORMATION,
      )
    } else {
      notify(
        "Specter.AI",
        "Следующая из очереди: ${next.label}",
        NotificationType.INFORMATION,
      )
    }
    ApplicationManager.getApplication().invokeLater {
      if (project.isDisposed || cancelRequested.get() || next.editor.isDisposed) {
        releaseBusy()
        return@invokeLater
      }
      startPending(next)
    }
  }

  private fun drainQueue(): Int {
    var n = 0
    while (queue.poll() != null) n++
    return n
  }

  private fun launchGeneration(
    editor: Editor,
    prompt: String,
    context: String,
    language: TargetLanguage,
    anchor: Int,
    typingSpeed: Int?,
    replaceRange: Pair<Int, Int>? = null,
    forceNearSlot: Boolean = false,
  ) {
    val settings = AiSettings.getInstance()

    val scale = RequestScale.planFor(prompt)
    val statusMode = progress.begin(scale.tier)

    if (!settings.hasApiKey()) {
      if (!settings.fallbackToTemplates) {
        failAndRelease("Нет API key. Добавь в Settings → SpecterAI", NotificationType.WARNING)
        return
      }
      notify(
        "Specter.AI",
        "Нет API key — локальный шаблон (${language.displayName}). Ключ: Settings → SpecterAI",
        NotificationType.WARNING,
      )
      val plan = AiCodeTemplates.planFor(prompt, language)
      startSmartSwarm(editor, plan, anchor, typingSpeed, replaceRange, forceNearSlot)
      return
    }

    val speedHint = typingSpeed?.let { " · speed $it" }.orEmpty()
    val thinkDetail =
      "${language.displayName} · ${AiModels.labelFor(settings.model)} · ~${scale.targetLines.first}–${scale.targetLines.last}$speedHint"
    if (statusMode) {
      progress.thinking(thinkDetail)
    } else {
      notify(
        "Specter.AI",
        "Думаю… $thinkDetail  (Esc — отмена)",
        NotificationType.INFORMATION,
      )
    }

    val future = AiClient.generateBlocksAsync(prompt, context, language)
    activeHttp.set(future)
    future.whenComplete { plan, error ->
      activeHttp.compareAndSet(future, null)
      ApplicationManager.getApplication().invokeLater {
        if (project.isDisposed || cancelRequested.get()) {
          progress.clear()
          releaseBusy(pump = !cancelRequested.get())
          return@invokeLater
        }
        if (error != null || plan == null) {
          if (isCancellation(error)) {
            progress.clear()
            releaseBusy(pump = !cancelRequested.get())
            return@invokeLater
          }
          
          if (AiClient.isCreditsError(error)) {
            failAndRelease(
              error?.message?.take(240)
                ?: "Не хватает кредитов OpenRouter. Пополни баланс и повтори.",
              NotificationType.ERROR,
            )
            return@invokeLater
          }
          if (!settings.fallbackToTemplates) {
            failAndRelease(
              "AI ошибка: ${error?.message?.take(180)}",
              NotificationType.ERROR,
            )
            return@invokeLater
          }
          
          notify(
            "Specter.AI",
            "AI ошибка: ${error?.message?.take(160)}. Fallback · ${language.displayName}.",
            NotificationType.ERROR,
          )
          startSmartSwarm(
            editor,
            AiCodeTemplates.planFor(prompt, language),
            anchor.coerceIn(0, editor.document.textLength),
            typingSpeed,
            replaceRange,
            forceNearSlot,
          )
          return@invokeLater
        }
        startSmartSwarm(
          editor,
          plan,
          anchor.coerceIn(0, editor.document.textLength),
          typingSpeed,
          replaceRange,
          forceNearSlot,
        )
      }
    }
  }

  private fun startSmartSwarm(
    editor: Editor,
    plan: AiCodeTemplates.SwarmPlan,
    anchor: Int,
    typingSpeed: Int?,
    replaceRange: Pair<Int, Int>? = null,
    forceNearSlot: Boolean = false,
  ) {
    if (cancelRequested.get()) {
      progress.clear()
      releaseBusy(pump = false)
      return
    }
    try {
      var limited = plan.copy(blocks = plan.blocks.take(AiSettings.getInstance().maxBlocks))
      if (forceNearSlot && limited.blocks.isNotEmpty()) {
        
        val primary = limited.blocks.first().copy(
          slot = SmartInsertPlanner.Slot.NEAR_CARET,
          label = limited.blocks.first().label.ifBlank { "fix" },
          targetPath = null,
        )
        limited = limited.copy(blocks = listOf(primary))
      }

      
      val multi = MultiFilePlanner.resolve(project, editor.virtualFile, limited.blocks)
      if (multi.sameFileBlocks.isEmpty() && multi.otherFileTargets.isEmpty()) {
        failAndRelease(
          "Нет блоков для вставки. ${MultiFilePlanner.summary(multi)}",
          NotificationType.WARNING,
        )
        return
      }

      
      commitSmartSwarm(editor, limited, multi, anchor, typingSpeed, replaceRange, forceNearSlot)
    } catch (t: Throwable) {
      failAndRelease(
        "Swarm ошибка: ${t.message?.take(180)}",
        NotificationType.ERROR,
      )
    }
  }

  
  private fun commitSmartSwarm(
    editor: Editor,
    limited: AiCodeTemplates.SwarmPlan,
    multi: MultiFilePlanner.ResolvedPlan,
    anchor: Int,
    typingSpeed: Int?,
    replaceRange: Pair<Int, Int>?,
    forceNearSlot: Boolean,
  ) {
    try {
      var insertAt = anchor.coerceIn(0, editor.document.textLength)
      if (replaceRange != null) {
        val (from, to) = replaceRange
        val start = from.coerceIn(0, editor.document.textLength)
        val end = to.coerceIn(start, editor.document.textLength)
        WriteCommandAction.runWriteCommandAction(project) {
          if (start < end) editor.document.deleteString(start, end)
        }
        insertAt = start.coerceIn(0, editor.document.textLength)
      }

      val profile = TypingSpeed.resolve(typingSpeed)
      val speedLabel = profile.speed?.let { "speed $it" } ?: "speed settings"
      val multiHint = when {
        multi.isMultiFile -> " · multi: ${MultiFilePlanner.summary(multi)}"
        multi.rejected.isNotEmpty() -> " · ${MultiFilePlanner.summary(multi)}"
        else -> ""
      }

      var swarmDetail = "$speedLabel"
      val swarm = MultiFileSwarm(project)
      activeSwarm.set(swarm)
      val jobSummary = swarm.start(
        primaryEditor = editor,
        resolved = multi,
        primaryAnchor = insertAt,
        typingSpeed = typingSpeed,
        forceNearSlot = forceNearSlot,
        onDone = {
          activeSwarm.compareAndSet(swarm, null)
          val cancelled = cancelRequested.get()
          if (!cancelled) {
            progress.done()
            val more = queue.size
            val doneMsg = if (more > 0) "Готово · далее в очереди: $more" else "Готово"
            notify("Specter.AI", doneMsg, NotificationType.INFORMATION)
          } else {
            progress.clear()
          }
          releaseBusy(pump = !cancelled)
        },
        onProgress = { percent ->
          progress.typing(swarmDetail, percent)
        },
      )

      if (jobSummary == "empty") {
        activeSwarm.compareAndSet(swarm, null)
        failAndRelease(
          "Нет блоков для вставки. ${MultiFilePlanner.summary(multi)}",
          NotificationType.WARNING,
        )
        return
      }

      swarmDetail = "$speedLabel: $jobSummary$multiHint"
      progress.typing(swarmDetail, 0)
      if (!progress.prefersStatus) {
        notify(
          "Specter.AI",
          "Swarm · $swarmDetail  (Esc — отмена)",
          NotificationType.INFORMATION,
        )
      }
    } catch (t: Throwable) {
      failAndRelease(
        "Swarm ошибка: ${t.message?.take(180)}",
        NotificationType.ERROR,
      )
    }
  }

  private fun beginBusy(): Boolean {
    if (!running.compareAndSet(false, true)) return false
    cancelRequested.set(false)
    return true
  }

  private fun releaseBusy(pump: Boolean = true) {
    running.set(false)
    activeHttp.set(null)
    activeSwarm.set(null)
    activeCeoTyper.set(null)
    activeVanish.set(null)
    if (pump && !cancelRequested.get() && !project.isDisposed) {
      pumpQueue()
    }
  }

  private fun failAndRelease(message: String, type: NotificationType) {
    progress.clear()
    notify("Specter.AI", message, type)
    releaseBusy(pump = !cancelRequested.get())
  }

  private fun previewPrompt(prompt: String, max: Int = 48): String {
    val oneLine = prompt.replace('\n', ' ').trim()
    if (oneLine.length <= max) return oneLine
    return oneLine.take(max - 1) + "…"
  }

  private fun isCancellation(error: Throwable?): Boolean {
    if (cancelRequested.get()) return true
    var t = error
    while (t != null) {
      if (t is java.util.concurrent.CancellationException) return true
      t = t.cause
    }
    return false
  }

  private fun extractContext(editor: Editor, start: Int, end: Int, fileName: String): String {
    val text = editor.document.text
    val from = (start - 1200).coerceAtLeast(0)
    val to = (end + 800).coerceAtMost(text.length)
    return buildString {
      appendLine("File: $fileName")
      append(text.substring(from, start))
      append("\n/* AI_COMMAND */\n")
      append(text.substring(end, to))
    }
  }

  private fun findCommand(editor: Editor): CommandMatch? {
    val match = AiCommandSyntax.findNearCaret(editor.document.text, editor.caretModel.offset)
      ?: return null
    return CommandMatch(
      start = match.start,
      end = match.end,
      prompt = match.prompt,
      typingSpeed = match.typingSpeed,
    )
  }

  private fun notify(title: String, content: String, type: NotificationType) {
    NotificationGroupManager.getInstance()
      .getNotificationGroup("Specter.AI")
      .createNotification(title, content, type)
      .notify(project)
  }

  private data class CommandMatch(
    val start: Int,
    val end: Int,
    val prompt: String,
    val typingSpeed: Int?,
  )

  private data class PendingJob(
    val editor: Editor,
    val prompt: String,
    val context: String,
    val language: TargetLanguage,
    val typingSpeed: Int?,
    val start: Int,
    val end: Int,
    val replaceRange: Pair<Int, Int>? = null,
    val forceNearSlot: Boolean = false,
    val label: String,
    val vanishCommand: Boolean,
  )

  companion object {
    private const val MAX_QUEUE = 8

    fun getInstance(project: Project): AiCommandRunner =
      project.getService(AiCommandRunner::class.java)
  }
}
