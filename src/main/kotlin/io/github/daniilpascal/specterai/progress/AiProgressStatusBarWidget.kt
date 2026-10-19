package io.github.daniilpascal.specterai.progress

import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.StatusBar
import com.intellij.openapi.wm.StatusBarWidget
import com.intellij.openapi.wm.StatusBarWidgetFactory
import com.intellij.util.Consumer
import java.awt.Component
import java.awt.event.MouseEvent

class AiProgressStatusBarWidgetFactory : StatusBarWidgetFactory {
  override fun getId(): String = AiProgressStatusBarWidget.ID

  override fun getDisplayName(): String = "SpecterAI Progress"

  override fun isAvailable(project: Project): Boolean = true

  override fun createWidget(project: Project): StatusBarWidget =
    AiProgressStatusBarWidget(project)

  override fun disposeWidget(widget: StatusBarWidget) {
    Disposer.dispose(widget)
  }

  override fun canBeEnabledOn(statusBar: StatusBar): Boolean = true
}

class AiProgressStatusBarWidget(
  project: Project,
) : StatusBarWidget, StatusBarWidget.TextPresentation {

  private val progress = AiProgress.getInstance(project)
  private var statusBar: StatusBar? = null

  init {
    progress.attachWidget(this)
  }

  override fun ID(): String = ID

  override fun install(statusBar: StatusBar) {
    this.statusBar = statusBar
  }

  override fun dispose() {
    progress.detachWidget(this)
    statusBar = null
  }

  override fun getPresentation(): StatusBarWidget.WidgetPresentation = this

  override fun getText(): String = progress.statusText()

  override fun getAlignment(): Float = Component.CENTER_ALIGNMENT

  override fun getTooltipText(): String =
    "SpecterAI — progress for LARGE/XLARGE requests (Esc to cancel)"

  override fun getClickConsumer(): Consumer<MouseEvent>? = null

  fun refresh() {
    statusBar?.updateWidget(ID())
  }

  companion object {
    const val ID = "SpecterAiProgress"
  }
}
