package io.github.daniilpascal.specterai.command

import com.intellij.codeInsight.editorActions.TypedHandlerDelegate
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiFile
import io.github.daniilpascal.specterai.settings.AiSettings

class AiCommandTypedHandler : TypedHandlerDelegate() {

  override fun charTyped(c: Char, project: Project, editor: Editor, file: PsiFile): Result {
    if (c != ';') return Result.CONTINUE
    if (!AiSettings.getInstance().enabled) return Result.CONTINUE
    AiCommandRunner.getInstance(project).tryHandle(editor)
    return Result.CONTINUE
  }
}
