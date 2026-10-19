package io.github.daniilpascal.specterai.optim


object OptimFactorExplain {

  private val DESCRIPTIONS = mapOf(
    "+short" to "Короткое тело — мало работы, проще оптимизировать вручную",
    "+compact" to "Компактная функция без лишнего объёма",
    "+flat" to "Плоская структура, почти без вложенности",
    "+simple-flow" to "Простой поток управления",
    "+early-return" to "Ранние return — меньше вложенности",
    "+structured-branch" to "when/switch вместо глубоких if",
    "+precondition" to "Явные проверки (require/check/assert)",
    "+lazy-sequence" to "Ленивые последовательности вместо лишних коллекций",
    "+declarative" to "Декларативный стиль (map/filter) без ручных циклов",
    "+hash-lookup" to "HashMap/HashSet для быстрых проверок",
    "+resource-use" to "Корректное закрытие ресурсов (use)",
    "+lazy-init" to "Ленивая инициализация",
    "+comprehension" to "List/dict/set comprehension вместо ручного цикла",
    "+async" to "async/await без лишней вложенности",
    "-hot-path" to "Горячий путь (main/render/handle…) — цена ошибок выше",
    "-huge-file" to "Очень большой файл — контекст дороже",
    "-large-file" to "Большой файл — локальные тормоза сильнее заметны",
    "-io-in-body" to "I/O в теле функции — риск блокировок / N+1",
    "-long" to "Длинное тело — сложнее читать и оптимизировать",
    "-very-long" to "Очень длинная функция — кандидат на разбиение",
    "-huge" to "Огромное тело — почти наверняка стоит упростить",
    "-nest3" to "Вложенность уровня 3",
    "-deep-nest" to "Глубокая вложенность — тяжело для CPU и чтения",
    "-branchy" to "Много ветвлений",
    "-complex-flow" to "Высокая цикломатическая сложность",
    "-nested-loops" to "Вложенные циклы — риск O(n²)+",
    "-multi-loop" to "Несколько циклов подряд",
    "-alloc-in-loop" to "Аллокации внутри цикла",
    "-string-concat-loop" to "Конкатенация строк в цикле",
    "-list-concat-loop" to "Конкатенация списков в цикле (лучше append)",
    "-sort-in-hot-path" to "Сортировка на горячем пути",
    "-list-contains-loop" to "Линейный поиск в цикле — лучше Set/Map",
    "-empty-catch" to "Пустой catch глотает ошибки",
    "-empty-except" to "except: pass глотает ошибки",
    "-printStackTrace" to "printStackTrace вместо нормального логирования",
    "-debug-print" to "Много print/console.log — шум и тормоза",
    "-sleep" to "sleep / длинная задержка в теле",
    "-await-in-loop" to "await в цикле — риск N+1 запросов",
    "-bare-goroutine" to "go без WaitGroup/errgroup — легко утечь",
    "-loose-eq" to "== вместо === в JS",
    "-todo" to "TODO/FIXME в теле",
    "-not-null-assert" to "Оператор !! — риск NPE",
    "-unsafe-cast" to "Небезопасный cast без as?",
    "-magic-numbers" to "Много «магических» чисел",
  )

  fun describe(factor: String): String =
    DESCRIPTIONS[factor] ?: when {
      factor.startsWith("+") -> "Плюс: $factor"
      factor.startsWith("-") -> "Минус: $factor"
      else -> factor
    }

  fun buildHtml(hit: FunctionOptimScanner.FunctionHit): String {
    val optim = hit.optim ?: return "<b>${hit.name}</b> — нет данных Optim"
    
    val factors = FunctionOptimScore.topFactors(optim.factors, limit = 2)
    val list = if (factors.isEmpty()) {
      "<i>Явных факторов нет — базовая оценка по размеру/структуре.</i>"
    } else {
      factors.joinToString("") { f ->
        val color = if (f.startsWith("+")) "#3daa6d" else if (f.startsWith("-")) "#d05a4a" else "#888"
        "<li><b style='color:$color'>$f</b> — ${describe(f)}</li>"
      }.let { "<ul>$it</ul>" }
    }
    val tone = when {
      optim.percent >= 75 -> "выглядит хорошо"
      optim.percent >= 45 -> "есть запас для улучшения"
      else -> "стоит упростить / ускорить"
    }
    return buildString {
      append("<html><body style='width:360px;font-family:sans-serif'>")
      append("<b>${hit.name}</b> · Optim <b>${optim.percent}%</b> — $tone<br><br>")
      append("<b>Главное</b>")
      append(list)
      append("<br><span style='color:#888'>Это статическая эвристика, не профилировщик.</span>")
      append("<br><span style='color:#888'>Fix with AI → preview → Accept / Reject. Esc — отклонить без правок.</span>")
      append("</body></html>")
    }
  }

  fun buildPlain(hit: FunctionOptimScanner.FunctionHit): String {
    val optim = hit.optim ?: return "${hit.name}: нет Optim"
    val lines = FunctionOptimScore.topFactors(optim.factors, limit = 2)
      .map { "• $it — ${describe(it)}" }
    return buildString {
      appendLine("${hit.name} · Optim ${optim.percent}%")
      if (lines.isEmpty()) appendLine("Явных факторов нет.")
      else lines.forEach { appendLine(it) }
    }.trim()
  }
}
