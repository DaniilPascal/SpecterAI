package io.github.daniilpascal.specterai.progress

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.WindowManager
import com.intellij.util.Alarm
import io.github.daniilpascal.specterai.ai.RequestScale


@Service(Service.Level.PROJECT)
class AiProgress(private val project: Project) {

  private val alarm = Alarm(Alarm.ThreadToUse.SWING_THREAD, project)
  private var useStatus = false
  private var tierLabel = ""
  private var phase = Phase.IDLE
  private var detail = ""
  private var percent: Int? = null
  private var widget: AiProgressStatusBarWidget? = null

  fun attachWidget(w: AiProgressStatusBarWidget) {
    widget = w
    refresh()
  }

  fun detachWidget(w: AiProgressStatusBarWidget) {
    if (widget === w) widget = null
  }

  
  val prefersStatus: Boolean
    get() = useStatus

  
  fun begin(tier: RequestScale.Tier): Boolean {
    useStatus = tier == RequestScale.Tier.LARGE || tier == RequestScale.Tier.XLARGE
    tierLabel = tier.name
    phase = Phase.IDLE
    detail = ""
    percent = null
    alarm.cancelAllRequests()
    refresh()
    return useStatus
  }

  fun thinking(detail: String) {
    if (!useStatus) return
    phase = Phase.THINKING
    this.detail = detail
    percent = null
    refresh()
  }

  fun typing(detail: String, percent: Int? = null) {
    if (!useStatus) return
    phase = Phase.TYPING
    this.detail = detail
    this.percent = percent?.coerceIn(0, 100)
    refresh()
  }

  fun done(briefMs: Long = 1600L) {
    if (!useStatus) {
      clear()
      return
    }
    phase = Phase.DONE
    detail = "Готово"
    percent = 100
    refresh()
    alarm.cancelAllRequests()
    alarm.addRequest({ clear() }, briefMs)
  }

  fun clear() {
    useStatus = false
    tierLabel = ""
    phase = Phase.IDLE
    detail = ""
    percent = null
    alarm.cancelAllRequests()
    refresh()
  }

  fun statusText(): String {
    if (phase == Phase.IDLE || !useStatus) return ""
    val pct = percent?.let { " $it%" }.orEmpty()
    val body = when (phase) {
      Phase.THINKING -> "Думаю… $detail"
      Phase.TYPING -> "Печать$pct · $detail"
      Phase.DONE -> detail
      Phase.IDLE -> ""
    }
    return "AI $tierLabel · $body  (Esc)"
  }

  private fun refresh() {
    val run = Runnable {
      if (project.isDisposed) return@Runnable
      widget?.refresh()
      if (widget == null) {
        WindowManager.getInstance().getStatusBar(project)?.info = statusText()
      } else if (phase == Phase.IDLE) {
        WindowManager.getInstance().getStatusBar(project)?.info = ""
      }
    }
    if (ApplicationManager.getApplication().isDispatchThread) run.run()
    else ApplicationManager.getApplication().invokeLater(run)
  }

  private enum class Phase { IDLE, THINKING, TYPING, DONE }

  companion object {
    fun getInstance(project: Project): AiProgress =
      project.getService(AiProgress::class.java)
  }
}
