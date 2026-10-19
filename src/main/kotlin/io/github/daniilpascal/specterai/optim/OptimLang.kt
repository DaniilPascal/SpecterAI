package io.github.daniilpascal.specterai.optim

import com.intellij.openapi.editor.Document
import com.intellij.openapi.fileEditor.FileDocumentManager
import io.github.daniilpascal.specterai.ai.TargetLanguage


enum class OptimLang {
  JVM,      
  PYTHON,
  JS,       
  GO,
  C_CPP,    
  GENERIC,
  ;

  companion object {
    fun fromDocument(document: Document): OptimLang {
      val file = FileDocumentManager.getInstance().getFile(document) ?: return GENERIC
      return fromFileName(file.name)
    }

    fun fromFileName(fileName: String): OptimLang =
      when (TargetLanguage.detect(fileName)) {
        TargetLanguage.KOTLIN, TargetLanguage.JAVA -> JVM
        TargetLanguage.PYTHON -> PYTHON
        TargetLanguage.JAVASCRIPT, TargetLanguage.TYPESCRIPT -> JS
        TargetLanguage.GO -> GO
        TargetLanguage.C, TargetLanguage.CPP -> C_CPP
        else -> GENERIC
      }
  }
}
