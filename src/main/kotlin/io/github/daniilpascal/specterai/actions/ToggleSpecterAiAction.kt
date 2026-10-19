package io.github.daniilpascal.specterai.actions

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.ToggleAction
import com.intellij.openapi.project.DumbAware
import io.github.daniilpascal.specterai.highlight.AiCommandSyntaxHighlighter
import io.github.daniilpascal.specterai.settings.AiSettings

class ToggleSpecterAiAction : ToggleAction(), DumbAware {

  override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

  override fun isSelected(e: AnActionEvent): Boolean = AiSettings.getInstance().enabled

  override fun setSelected(e: AnActionEvent, state: Boolean) {
    AiSettings.getInstance().enabled = state
    AiCommandSyntaxHighlighter.refreshAllEditors()
  }
}
