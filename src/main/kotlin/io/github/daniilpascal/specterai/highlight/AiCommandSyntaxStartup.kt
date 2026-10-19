package io.github.daniilpascal.specterai.highlight

import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity

class AiCommandSyntaxStartup : ProjectActivity {
  override suspend fun execute(project: Project) {
    AiCommandSyntaxHighlighter.attachToOpenEditors()
  }
}
