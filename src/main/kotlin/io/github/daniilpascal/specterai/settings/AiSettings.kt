package io.github.daniilpascal.specterai.settings

import com.intellij.credentialStore.CredentialAttributes
import com.intellij.credentialStore.generateServiceName
import com.intellij.ide.passwordSafe.PasswordSafe
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import io.github.daniilpascal.specterai.ai.AiModels

@Service(Service.Level.APP)
@State(name = "SpecterAiSettings", storages = [Storage("SpecterAiSettings.xml")])
class AiSettings : PersistentStateComponent<AiSettings.State> {

  data class State(
    var enabled: Boolean = true,
    var baseUrl: String = DEFAULT_BASE_URL,
    var model: String = DEFAULT_MODEL,
    var typingDelayMs: Int = DEFAULT_TYPING_DELAY_MS,
    var fallbackToTemplates: Boolean = true,
    var maxBlocks: Int = DEFAULT_MAX_BLOCKS,
    var optimHintsEnabled: Boolean = true,
    var optimShowFactors: Boolean = false,
    var optimShowFunctionName: Boolean = true,
    var optimRefreshDelayMs: Int = DEFAULT_OPTIM_REFRESH_MS,
    var diffPreviewEnabled: Boolean = false,
    var lastSwarmMs: Long = 0,
    var lastSwarmBlocks: Int = 0,
    var lastSwarmFiles: Int = 0,
    var lastSwarmAtEpochMs: Long = 0,
  )

  private var state = State()

  @Volatile
  private var cachedHasApiKey: Boolean = false

  init {
    ApplicationManager.getApplication().executeOnPooledThread {
      refreshApiKeyCache()
    }
  }

  override fun getState(): State = state

  override fun loadState(state: State) {
    this.state = state
    normalize()
  }

  var enabled: Boolean
    get() = state.enabled
    set(value) {
      state.enabled = value
    }

  var baseUrl: String
    get() = state.baseUrl.ifBlank { DEFAULT_BASE_URL }
    set(value) {
      state.baseUrl = value.trim().ifBlank { DEFAULT_BASE_URL }
    }

  var model: String
    get() = AiModels.migrateIfLegacy(state.model.ifBlank { DEFAULT_MODEL })
    set(value) {
      state.model = AiModels.migrateIfLegacy(value.trim().ifBlank { DEFAULT_MODEL })
    }

  var typingDelayMs: Int
    get() = state.typingDelayMs.coerceIn(MIN_TYPING_DELAY_MS, MAX_TYPING_DELAY_MS)
    set(value) {
      state.typingDelayMs = value.coerceIn(MIN_TYPING_DELAY_MS, MAX_TYPING_DELAY_MS)
    }

  var fallbackToTemplates: Boolean
    get() = state.fallbackToTemplates
    set(value) {
      state.fallbackToTemplates = value
    }

  var maxBlocks: Int
    get() = state.maxBlocks.coerceIn(1, 4)
    set(value) {
      state.maxBlocks = value.coerceIn(1, 4)
    }

  var optimHintsEnabled: Boolean
    get() = state.optimHintsEnabled
    set(value) {
      state.optimHintsEnabled = value
    }

  var optimShowFactors: Boolean
    get() = state.optimShowFactors
    set(value) {
      state.optimShowFactors = value
    }

  var optimShowFunctionName: Boolean
    get() = state.optimShowFunctionName
    set(value) {
      state.optimShowFunctionName = value
    }

  var optimRefreshDelayMs: Int
    get() = state.optimRefreshDelayMs.coerceIn(MIN_OPTIM_REFRESH_MS, MAX_OPTIM_REFRESH_MS)
    set(value) {
      state.optimRefreshDelayMs = value.coerceIn(MIN_OPTIM_REFRESH_MS, MAX_OPTIM_REFRESH_MS)
    }

  var diffPreviewEnabled: Boolean
    get() = state.diffPreviewEnabled
    set(value) {
      state.diffPreviewEnabled = value
    }

  val lastSwarmMs: Long get() = state.lastSwarmMs.coerceAtLeast(0)
  val lastSwarmBlocks: Int get() = state.lastSwarmBlocks.coerceAtLeast(0)
  val lastSwarmFiles: Int get() = state.lastSwarmFiles.coerceAtLeast(0)
  val lastSwarmAtEpochMs: Long get() = state.lastSwarmAtEpochMs.coerceAtLeast(0)

  fun recordSwarm(durationMs: Long, blocks: Int, files: Int) {
    state.lastSwarmMs = durationMs.coerceAtLeast(0)
    state.lastSwarmBlocks = blocks.coerceAtLeast(0)
    state.lastSwarmFiles = files.coerceAtLeast(0)
    state.lastSwarmAtEpochMs = System.currentTimeMillis()
  }

  fun lastSwarmSummary(): String {
    if (state.lastSwarmAtEpochMs <= 0L) return "—"
    return "${lastSwarmMs} ms · ${lastSwarmBlocks} blocks · ${lastSwarmFiles} files"
  }

  fun getApiKey(): String {
    val key = PasswordSafe.instance.getPassword(credentialAttributes()).orEmpty()
    cachedHasApiKey = key.isNotBlank()
    return key
  }

  fun setApiKey(key: String) {
    PasswordSafe.instance.setPassword(credentialAttributes(), key.ifBlank { null })
    cachedHasApiKey = key.isNotBlank()
  }

  fun clearApiKey() {
    PasswordSafe.instance.setPassword(credentialAttributes(), null)
    cachedHasApiKey = false
  }

  fun hasApiKey(): Boolean {
    if (cachedHasApiKey) return true
    refreshApiKeyCache()
    return cachedHasApiKey
  }

  fun refreshApiKeyCache() {
    cachedHasApiKey = PasswordSafe.instance.getPassword(credentialAttributes()).orEmpty().isNotBlank()
  }

  private fun normalize() {
    state.typingDelayMs = state.typingDelayMs.coerceIn(MIN_TYPING_DELAY_MS, MAX_TYPING_DELAY_MS)
    state.maxBlocks = state.maxBlocks.coerceIn(1, 4)
    state.optimRefreshDelayMs =
      state.optimRefreshDelayMs.coerceIn(MIN_OPTIM_REFRESH_MS, MAX_OPTIM_REFRESH_MS)
    if (state.baseUrl.isBlank()) state.baseUrl = DEFAULT_BASE_URL
    if (state.model.isBlank()) state.model = DEFAULT_MODEL
    if (state.model == "openai/gpt-4o-mini") {
      state.model = DEFAULT_MODEL
    }
    state.model = AiModels.migrateIfLegacy(state.model)
  }

  private fun credentialAttributes(): CredentialAttributes =
    CredentialAttributes(generateServiceName("SpecterAI", "AI API Key"))

  companion object {
    const val DEFAULT_BASE_URL = "https://openrouter.ai/api/v1"
    const val DEFAULT_MODEL = "anthropic/claude-sonnet-5.5"
    const val DEFAULT_TYPING_DELAY_MS = 16
    const val MIN_TYPING_DELAY_MS = 8
    const val MAX_TYPING_DELAY_MS = 40
    const val DEFAULT_MAX_BLOCKS = 3
    const val DEFAULT_OPTIM_REFRESH_MS = 120
    const val MIN_OPTIM_REFRESH_MS = 40
    const val MAX_OPTIM_REFRESH_MS = 800

    fun getInstance(): AiSettings =
      ApplicationManager.getApplication().getService(AiSettings::class.java)
  }
}