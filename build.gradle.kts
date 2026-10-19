import org.jetbrains.intellij.platform.gradle.IntelliJPlatformType
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
  id("java")
  id("org.jetbrains.kotlin.jvm") version "2.4.0"
  id("org.jetbrains.intellij.platform") version "2.19.0"
}

group = "io.github.daniilpascal"
version = "1.0.0"

repositories {
  mavenCentral()
  intellijPlatform {
    defaultRepositories()
  }
}

dependencies {
  intellijPlatform {
    val useLocal = providers.gradleProperty("useLocalIde").orElse("false").get().toBoolean()
    val localPath = providers.gradleProperty("localIdePath")
      .orElse("/Applications/IntelliJ IDEA.app")
      .get()
    val platformVersion = providers.gradleProperty("platformVersion").orElse("2026.2.3").get()
    val product = providers.gradleProperty("platformProduct").orElse("idea").get()
      .trim()
      .lowercase()
    val localIdea = file(localPath)
    if (useLocal && localIdea.isDirectory) {
      local(localIdea)
    } else {
      when (product) {
        "idea", "intellij", "iu", "ic" -> intellijIdea(platformVersion)
        "pycharm", "py" -> pycharm(platformVersion)
        "clion", "cl" -> clion(platformVersion)
        "androidstudio", "android", "as" -> androidStudio(platformVersion)
        "webstorm", "ws" -> webstorm(platformVersion)
        "goland", "go" -> goland(platformVersion)
        "rustrover", "rr", "rust" -> rustRover(platformVersion)
        "phpstorm", "ps" -> phpstorm(platformVersion)
        "rubymine", "rm" -> rubymine(platformVersion)
        "rider", "rd" -> rider(platformVersion)
        "datagrip", "dg" -> datagrip(platformVersion)
        else -> error(
          "Unknown platformProduct='$product'. Use: idea, pycharm, clion, androidStudio, " +
            "webstorm, goland, rustRover, phpstorm, rubyMine, rider, dataGrip",
        )
      }
    }
    pluginVerifier()
  }
  testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
  testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
  useJUnitPlatform()
}

kotlin {
  jvmToolchain(21)
  compilerOptions {
    jvmTarget.set(JvmTarget.JVM_21)
  }
}

java {
  toolchain {
    languageVersion.set(JavaLanguageVersion.of(21))
  }
}

intellijPlatform {
  pluginConfiguration {
    name = "SpecterAI"
    ideaVersion {
      sinceBuild = providers.gradleProperty("pluginSinceBuild").orElse("251")
      untilBuild = provider { null }
    }
  }
  pluginVerification {
    freeArgs = listOf(
      "-mute",
      "TemplateWordInPluginName",
    )
    ides {
      create(IntelliJPlatformType.IntellijIdeaCommunity, "2025.1")
      val localPath = providers.gradleProperty("localIdePath")
        .orElse("/Applications/IntelliJ IDEA.app")
        .get()
      val localIdea = file(localPath)
      if (localIdea.isDirectory) {
        local(localIdea)
      }
    }
  }
}

fun writeSandboxDisabledPlugins() {
  val sandboxRoot = layout.projectDirectory.file(".intellijPlatform/sandbox").asFile
  if (!sandboxRoot.isDirectory) return

  val targets = sandboxRoot.walkTopDown()
    .maxDepth(8)
    .filter { it.isFile && it.name == "disabled_plugins.txt" }
    .toMutableList()

  if (targets.isEmpty()) {
    sandboxRoot.walkTopDown()
      .maxDepth(6)
      .filter { it.isDirectory && it.name.startsWith("config") }
      .forEach { configDir ->
        val f = configDir.resolve("disabled_plugins.txt")
        targets += f
      }
  }

  for (disabled in targets) {
    disabled.parentFile.mkdirs()
    val keep = disabled.takeIf { it.exists() }
      ?.readLines()
      ?.map { it.trim() }
      ?.filter { it.isNotEmpty() && it != "com.intellij.modules.ultimate" }
      ?.toMutableSet()
      ?: mutableSetOf()
    keep += "org.jetbrains.plugins.gitlab"
    disabled.writeText(keep.joinToString("\n", postfix = "\n"))
  }
}

val fixSandboxDisabledPlugins = tasks.register("fixSandboxDisabledPlugins") {
  doLast { writeSandboxDisabledPlugins() }
}

tasks.matching { it.name.startsWith("prepareSandbox") }.configureEach {
  finalizedBy(fixSandboxDisabledPlugins)
}

tasks.matching { it.name == "runIde" }.configureEach {
  val exec = this as org.gradle.api.tasks.JavaExec
  dependsOn(fixSandboxDisabledPlugins)
  exec.maxHeapSize = "768m"
  exec.minHeapSize = "192m"
  exec.systemProperty("idea.paths.selector", "SpecterAiSandbox")
  exec.jvmArgs("-XX:ReservedCodeCacheSize=256m")
  doFirst { writeSandboxDisabledPlugins() }
}
