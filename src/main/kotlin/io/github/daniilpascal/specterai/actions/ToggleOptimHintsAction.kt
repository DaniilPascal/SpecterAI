package io.github.daniilpascal.specterai.actions

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.ToggleAction
import com.intellij.openapi.project.DumbAware
import io.github.daniilpascal.specterai.optim.FunctionOptimInlayController
import io.github.daniilpascal.specterai.settings.AiSettings


class ToggleOptimHintsAction : ToggleAction(), DumbAware {

  override fun isSelected(e: AnActionEvent): Boolean =
    AiSettings.getInstance().optimHintsEnabled

  override fun setSelected(e: AnActionEvent, state: Boolean) {
    AiSettings.getInstance().optimHintsEnabled = state
    FunctionOptimInlayController.refreshAllEditors()
  }

  override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
}
