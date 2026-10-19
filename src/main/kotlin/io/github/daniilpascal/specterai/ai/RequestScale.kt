package io.github.daniilpascal.specterai.ai


object RequestScale {

  enum class Tier {
    TINY,
    SMALL,
    MEDIUM,
    LARGE,
    XLARGE,
  }

  data class Plan(
    val tier: Tier,
    val maxTokens: Int,
    val temperature: Double,
    val contextChars: Int,
    val preferredBlocks: Int,
    val targetLines: IntRange,
    val sizeInstruction: String,
  )

  fun planFor(prompt: String): Plan {
    val p = prompt.lowercase().trim()
    val tier = classify(p)
    return when (tier) {
      Tier.TINY -> Plan(
        tier = tier,
        maxTokens = 500,
        temperature = 0.2,
        contextChars = 2500,
        preferredBlocks = 1,
        targetLines = 3..15,
        sizeInstruction = """
          Scope: TINY. Keep it minimal — only what was asked (e.g. hello world / one function).
          Target about 3–15 lines of Kotlin total. Do NOT invent extra classes or features.
        """.trimIndent(),
      )
      Tier.SMALL -> Plan(
        tier = tier,
        maxTokens = 1000,
        temperature = 0.25,
        contextChars = 4000,
        preferredBlocks = 2,
        targetLines = 15..40,
        sizeInstruction = """
          Scope: SMALL. A focused snippet or small helper — complete and runnable.
          Target about 15–40 lines. No large frameworks or unused scaffolding.
        """.trimIndent(),
      )
      Tier.MEDIUM -> Plan(
        tier = tier,
        maxTokens = 2000,
        temperature = 0.3,
        contextChars = 8000,
        preferredBlocks = 3,
        targetLines = 40..100,
        sizeInstruction = """
          Scope: MEDIUM. Implement the request fully with real logic (not stubs).
          Target about 40–100 lines across blocks. Include needed imports and types.
        """.trimIndent(),
      )
      Tier.LARGE -> Plan(
        tier = tier,
        
        maxTokens = 2500,
        temperature = 0.3,
        contextChars = 10000,
        preferredBlocks = 3,
        targetLines = 80..160,
        sizeInstruction = """
          Scope: LARGE. Deliver a compact WORKING solution for the whole request.
          Target about 80–160 lines. Prefer complete types/methods over TODOs.
          CRITICAL: never truncate mid-token or mid-brace. Every class/method must be complete.
          No skeletons, no "// TODO implement", no cut-off identifiers like ServerSoc.
          Prefer a finished short demo over a huge unfinished system.
        """.trimIndent(),
      )
      Tier.XLARGE -> Plan(
        tier = tier,
        maxTokens = 4000,
        temperature = 0.3,
        contextChars = 12000,
        preferredBlocks = 4,
        targetLines = 120..220,
        sizeInstruction = """
          Scope: XLARGE (compact). Build a coherent educational mini-system (e.g. VPN → small
          client+server on sockets with a forward loop). Stay COMPLETE within ~120–220 lines.
          CRITICAL: finish every brace/paren/string. Never cut output mid-identifier.
          Prefer a working demo over a partial production stack. Do not pad with comments.
        """.trimIndent(),
      )
    }
  }

  fun classify(promptLower: String): Tier {
    val p = promptLower
    
    when {
      containsAny(p, "минимум 200", "200+ строк", "200 строк", "очень много", "huge", "enterprise", "microservice", "микросервис") ->
        return Tier.XLARGE
      
      containsAny(
        p,
        "vpn", "впн", "proxy", "прокси", "туннел", "tunnel", "socks",
        "чат сервер", "чат-сервер", "game server", "игровой сервер",
        "торрент", "torrent", "blockchain", "блокчейн", "компилятор", "compiler",
        "операционн", "os kernel", "браузер", "browser engine",
      ) -> return Tier.XLARGE
      containsAny(p, "минимум 100", "100+ строк", "100 строк", "полный проект", "весь модуль", "с тестами и", "production") ->
        return Tier.LARGE
      containsAny(
        p,
        "сервер", "server", "клиент-сервер", "client-server", "сокет", "socket",
        "шифр", "encrypt", "tls", "ssl", "http server", "tcp", "udp",
        "мессенджер", "messenger", "бот", "bot ",
      ) -> return Tier.LARGE
      containsAny(p, "минимум 50", "50+ строк", "подробно", "развёрнут", "развернут", "полный класс", "целиком") ->
        return Tier.MEDIUM
      containsAny(p, "коротко", "кратко", "минимум кода", "one-liner", "одна строка", "только println") ->
        return Tier.TINY
    }

    var score = 0
    score += when {
      p.length >= 220 -> 3
      p.length >= 120 -> 2
      p.length >= 60 -> 1
      else -> 0
    }

    
    if (containsAny(p, "hello world", "helloworld", "привет мир", "println", "print hello")) {
      score -= 4
    }
    if (containsAny(p, "пример", "snippet", "сниппет", "helper", "утилита", "функци")) {
      score += 0
    }

    
    if (containsAny(p, "функцию", "метод", "валидац", "парсер", "converter", "dto", "enum")) {
      score += 1
    }

    
    if (containsAny(
        p,
        "класс", "data class", "repository", "service", "use case", "usecase",
        "viewmodel", "adapter", "interceptor", "middleware",
      )
    ) {
      score += 2
    }

    
    if (containsAny(
        p,
        "crud", "rest api", "api", "авторизац", "auth", "login", "jwt",
        "база", "database", "sql", "room", "entity", "сущност",
        "ui экран", "экран", "fragment", "activity", "compose",
        "напиши свой", "сделай свой", "реализуй", "implement a", "build a",
      )
    ) {
      score += 3
    }

    
    if (containsAny(
        p,
        "полный", "систему", "плагин", "архитектур", "clean architecture",
        "с тестами", "unit test", "интеграцион", "эндпоинт", "несколько классов",
        "модуль", "библиотеку", "framework",
      )
    ) {
      score += 3
    }

    
    val andCount = Regex("""\bи\b|,|;|/\s*|\d+\)|\d+\.""").findAll(p).count()
    if (andCount >= 4) score += 2
    else if (andCount >= 2) score += 1

    return when {
      score <= 0 -> Tier.TINY
      score <= 2 -> Tier.SMALL
      score <= 5 -> Tier.MEDIUM
      score <= 8 -> Tier.LARGE
      else -> Tier.XLARGE
    }
  }

  private fun containsAny(text: String, vararg needles: String): Boolean =
    needles.any { text.contains(it) }
}
