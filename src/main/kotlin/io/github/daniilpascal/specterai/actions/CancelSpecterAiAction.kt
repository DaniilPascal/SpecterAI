package io.github.daniilpascal.specterai.actions

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAware
import io.github.daniilpascal.specterai.command.AiCommandRunner


class CancelSpecterAiAction : AnAction(), DumbAware {

  override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

  override fun update(e: AnActionEvent) {
    val project = e.project
    e.presentation.isEnabledAndVisible =
      project != null && !project.isDisposed && AiCommandRunner.getInstance(project).isBusy
  }

  override fun actionPerformed(e: AnActionEvent) {
    val project = e.project ?: return
    AiCommandRunner.getInstance(project).cancel("Отменено (Esc)")
  }
}
