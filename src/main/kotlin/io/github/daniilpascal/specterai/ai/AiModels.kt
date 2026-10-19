package io.github.daniilpascal.specterai.ai


object AiModels {

  data class Preset(
    val id: String,
    val label: String,
    
    val tier: String,
  ) {
    override fun toString(): String = label
  }

  val FAST = Preset(
    id = "openai/gpt-4o-mini",
    label = "Fast · GPT-4o mini",
    tier = "fast",
  )

  val BALANCED = Preset(
    id = "openai/gpt-4o",
    label = "Balanced · GPT-4o",
    tier = "balanced",
  )

  val POWER = Preset(
    id = "anthropic/claude-sonnet-5.5",
    label = "Power · Claude Sonnet 5.5",
    tier = "power",
  )

  val MAX = Preset(
    id = "anthropic/claude-opus-5.5",
    label = "Max · Claude Opus 5.5",
    tier = "max",
  )

  val DEEPSEEK = Preset(
    id = "deepseek/deepseek-r1",
    label = "Reasoning · DeepSeek R1",
    tier = "reason",
  )

  val GEMINI = Preset(
    id = "google/gemini-2.5-pro",
    label = "Pro · Gemini 2.5 Pro",
    tier = "pro",
  )

  
  val ALL: List<Preset> = listOf(FAST, BALANCED, POWER, MAX, DEEPSEEK, GEMINI)

  const val CUSTOM_LABEL = "Custom…"

  
  private val LEGACY_IDS = setOf(
    "anthropic/claude-sonnet-4",
    "anthropic/claude-opus-4.1",
  )

  fun migrateIfLegacy(modelId: String): String {
    val id = modelId.trim()
    if (id.isBlank()) return POWER.id
    if (id in LEGACY_IDS) return POWER.id
    return id
  }

  fun findById(modelId: String): Preset? =
    ALL.firstOrNull { it.id.equals(modelId.trim(), ignoreCase = true) }

  fun labelFor(modelId: String): String =
    findById(modelId)?.label ?: modelId.ifBlank { POWER.label }
}
