package io.github.daniilpascal.specterai.optim

import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity


class FunctionOptimStartup : ProjectActivity {
  override suspend fun execute(project: Project) {
    if (project.isDisposed) return
    FunctionOptimInlayController.attachToOpenEditors()
  }
}
