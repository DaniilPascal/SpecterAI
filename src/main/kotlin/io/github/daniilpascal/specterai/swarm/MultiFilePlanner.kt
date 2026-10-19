package io.github.daniilpascal.specterai.swarm

import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import io.github.daniilpascal.specterai.ai.AiCodeTemplates


object MultiFilePlanner {

  data class Target(
    val block: AiCodeTemplates.SemanticBlock,
    
    val relativePath: String?,
    
    val virtualFile: VirtualFile?,
    
    val needsCreate: Boolean,
    
    val rejectReason: String? = null,
  ) {
    val isCurrentFile: Boolean get() = relativePath == null && rejectReason == null
    val isUsable: Boolean get() = rejectReason == null
  }

  data class ResolvedPlan(
    val currentFile: VirtualFile?,
    val targets: List<Target>,
  ) {
    val sameFileBlocks: List<AiCodeTemplates.SemanticBlock>
      get() = targets.filter { it.isCurrentFile || isSameAsCurrent(it) }.map { it.block }

    val otherFileTargets: List<Target>
      get() = targets.filter {
        it.isUsable && !it.isCurrentFile && !isSameAsCurrent(it)
      }

    val rejected: List<Target>
      get() = targets.filter { it.rejectReason != null }

    val isMultiFile: Boolean get() = otherFileTargets.isNotEmpty()

    private fun isSameAsCurrent(t: Target): Boolean {
      val cur = currentFile ?: return false
      if (t.virtualFile != null && t.virtualFile == cur) return true
      val rel = t.relativePath ?: return false
      return rel.equals(cur.name, ignoreCase = true) ||
        cur.path.replace('\\', '/').endsWith("/$rel")
    }
  }

  fun resolve(
    project: Project,
    currentFile: VirtualFile?,
    blocks: List<AiCodeTemplates.SemanticBlock>,
  ): ResolvedPlan {
    val baseDir = currentFile?.parent
    val targets = blocks.map { block ->
      val raw = block.targetPath
      if (raw.isNullOrBlank()) {
        return@map Target(
          block = block,
          relativePath = null,
          virtualFile = currentFile,
          needsCreate = false,
        )
      }
      val sanitized = sanitizeRelativePath(raw)
      if (sanitized == null) {
        return@map Target(
          block = block.copy(targetPath = null),
          relativePath = null,
          virtualFile = currentFile,
          needsCreate = false,
          rejectReason = "небезопасный путь: ${raw.take(80)}",
        )
      }
      if (!isUnderProject(project, baseDir, sanitized)) {
        return@map Target(
          block = block.copy(targetPath = null),
          relativePath = null,
          virtualFile = currentFile,
          needsCreate = false,
          rejectReason = "путь вне проекта: $sanitized",
        )
      }
      val existing = findExisting(project, currentFile, baseDir, sanitized)
      Target(
        block = block.copy(targetPath = sanitized),
        relativePath = sanitized,
        virtualFile = existing,
        needsCreate = existing == null,
      )
    }
    return ResolvedPlan(currentFile = currentFile, targets = targets)
  }

  
  fun sanitizeRelativePath(raw: String): String? {
    var p = raw.trim()
      .trim('"', '\'', '`')
      .replace('\\', '/')
      .replace(Regex("""^\./+"""), "")
    if (p.isBlank()) return null
    
    p = p.removePrefix("file:").trim()
    if (p.isBlank()) return null
    if (p.length > 240) return null
    if (p.startsWith("/") || Regex("""^[A-Za-z]:/""").containsMatchIn(p)) return null
    if (p.contains("://") || p.startsWith("~")) return null
    val parts = p.split('/').filter { it.isNotEmpty() }
    if (parts.isEmpty()) return null
    if (parts.any { it == ".." || it == "." }) return null
    if (parts.any { !SAFE_SEGMENT.matches(it) }) return null
    return parts.joinToString("/")
  }

  private fun findExisting(
    project: Project,
    currentFile: VirtualFile?,
    baseDir: VirtualFile?,
    relative: String,
  ): VirtualFile? {
    
    if (baseDir != null) {
      VfsUtilCore.findRelativeFile(relative, baseDir)?.takeIf { it.isValid && !it.isDirectory }
        ?.let { return it }
    }
    
    val base = project.guessProjectDir() ?: return null
    VfsUtilCore.findRelativeFile(relative, base)?.takeIf { it.isValid && !it.isDirectory }
      ?.let { return it }
    
    val simple = relative.substringAfterLast('/')
    if (baseDir != null && simple != relative) {
      baseDir.findChild(simple)?.takeIf { it.isValid && !it.isDirectory }?.let { return it }
    }
    
    if (currentFile != null && currentFile.name.equals(simple, ignoreCase = true)) {
      return currentFile
    }
    return null
  }

  private fun isUnderProject(
    project: Project,
    baseDir: VirtualFile?,
    relative: String,
  ): Boolean {
    val projectBase = project.guessProjectDir() ?: return true 
    val anchor = baseDir ?: projectBase
    
    val anchorPath = anchor.path.replace('\\', '/').trimEnd('/')
    val projectPath = projectBase.path.replace('\\', '/').trimEnd('/')
    val resolved = "$anchorPath/$relative"
    val normalized = normalizedPath(resolved)
    val projectNorm = normalizedPath(projectPath)
    return normalized == projectNorm || normalized.startsWith("$projectNorm/")
  }

  private fun normalizedPath(path: String): String {
    val parts = mutableListOf<String>()
    for (seg in path.replace('\\', '/').split('/')) {
      when (seg) {
        "", "." -> Unit
        ".." -> if (parts.isNotEmpty()) parts.removeAt(parts.lastIndex)
        else -> parts += seg
      }
    }
    return if (path.startsWith("/")) parts.joinToString("/", prefix = "/")
    else parts.joinToString("/")
  }

  fun summary(plan: ResolvedPlan): String {
    val same = plan.sameFileBlocks.size
    val other = plan.otherFileTargets.map {
      val mark = if (it.needsCreate) "+" else ""
      "$mark${it.relativePath}"
    }
    val bad = plan.rejected.mapNotNull { it.rejectReason }
    return buildString {
      append("текущий×$same")
      if (other.isNotEmpty()) append(" · файлы: ${other.joinToString(", ")}")
      if (bad.isNotEmpty()) append(" · skip: ${bad.joinToString("; ").take(80)}")
    }
  }

  
  fun ensureFile(
    project: Project,
    currentFile: VirtualFile?,
    relativePath: String,
  ): VirtualFile? {
    val sanitized = sanitizeRelativePath(relativePath) ?: return null
    if (!isUnderProject(project, currentFile?.parent, sanitized)) return null
    findExisting(project, currentFile, currentFile?.parent, sanitized)?.let { return it }

    val baseDir = currentFile?.parent
      ?: project.guessProjectDir()
      ?: return null

    var created: VirtualFile? = null
    WriteCommandAction.runWriteCommandAction(project) {
      try {
        var dir = baseDir
        val parts = sanitized.split('/').filter { it.isNotEmpty() }
        if (parts.isEmpty()) return@runWriteCommandAction
        for (i in 0 until parts.lastIndex) {
          val name = parts[i]
          dir = dir.findChild(name) ?: dir.createChildDirectory(this, name)
        }
        val fileName = parts.last()
        created = dir.findChild(fileName) ?: dir.createChildData(this, fileName)
      } catch (_: Throwable) {
        created = null
      }
    }
    created?.let { LocalFileSystem.getInstance().refreshAndFindFileByPath(it.path) }
    return created?.takeIf { it.isValid }
  }

  private val SAFE_SEGMENT = Regex("""[A-Za-z0-9_][A-Za-z0-9_.,+\-]*""")
}
