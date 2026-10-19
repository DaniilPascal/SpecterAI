package io.github.daniilpascal.specterai.optim


object FunctionOptimScore {

  data class Breakdown(
    val percent: Int,
    val factors: List<String>,
  )

  
  data class Context(
    val functionName: String = "",
    
    val fileLineCount: Int = 0,
  )

  fun score(body: String, lang: OptimLang = OptimLang.GENERIC, ctx: Context = Context()): Int =
    analyze(body, lang, ctx).percent

  fun analyze(
    body: String,
    lang: OptimLang = OptimLang.GENERIC,
    ctx: Context = Context(),
  ): Breakdown {
    val factors = mutableListOf<String>()
    var score = 62

    val lines = body.lines()
      .map { it.trim() }
      .filter { it.isNotEmpty() && !it.startsWith("//") && !it.startsWith("#") }
    val code = stripComments(body, lang)
    val depth = nestingDepth(code, lang)
    val cyclomatic = estimateCyclomatic(code)
    val loops = countLoops(code, lang)
    val nestedLoops = hasNestedLoops(code, lang)

    
    when {
      lines.size <= 3 -> {
        score += 16
        factors += "+short"
      }
      lines.size <= 10 -> {
        score += 10
        factors += "+compact"
      }
      lines.size <= 20 -> {
        score += 2
      }
      lines.size <= 35 -> {
        score -= 10
        factors += "-long"
      }
      lines.size <= 60 -> {
        score -= 18
        factors += "-very-long"
      }
      else -> {
        score -= 28
        factors += "-huge"
      }
    }

    
    when {
      depth <= 1 -> {
        score += 12
        factors += "+flat"
      }
      depth == 2 -> score += 5
      depth == 3 -> {
        score -= 6
        factors += "-nest3"
      }
      else -> {
        score -= 16
        factors += "-deep-nest"
      }
    }

    
    when {
      cyclomatic <= 2 -> {
        score += 8
        factors += "+simple-flow"
      }
      cyclomatic <= 5 -> score += 2
      cyclomatic <= 9 -> {
        score -= 8
        factors += "-branchy"
      }
      else -> {
        score -= 16
        factors += "-complex-flow"
      }
    }

    
    when {
      nestedLoops -> {
        score -= 18
        factors += "-nested-loops"
      }
      loops >= 2 -> {
        score -= 8
        factors += "-multi-loop"
      }
      loops == 1 && depth <= 2 -> {
        score += 3
      }
    }

    if (allocInLoop(code, lang)) {
      score -= 12
      factors += "-alloc-in-loop"
    }
    if (stringConcatInLoop(code, lang)) {
      score -= 14
      factors += "-string-concat-loop"
    }
    if (sortInHotPath(code, lang) && loops >= 1) {
      score -= 10
      factors += "-sort-in-hot-path"
    }
    if (linearLookupInLoop(code, lang) && loops >= 1) {
      score -= 8
      factors += "-list-contains-loop"
    }

    
    if (Regex("""\bTODO\b|\bFIXME\b|\bXXX\b""").containsMatchIn(body)) {
      score -= 10
      factors += "-todo"
    }
    val magicNumbers = Regex("""(?<![.\w])\b(?!0|1|2|10|100)\d{2,}\b""").findAll(code).count()
    if (magicNumbers >= 3) {
      score -= 6
      factors += "-magic-numbers"
    }

    when (lang) {
      OptimLang.JVM, OptimLang.GENERIC, OptimLang.C_CPP ->
        applyJvmFactors(code, lines, depth, loops, factors).also { score += it }
      OptimLang.PYTHON -> applyPythonFactors(code, lines, depth, loops, factors).also { score += it }
      OptimLang.JS -> applyJsFactors(code, lines, depth, loops, factors).also { score += it }
      OptimLang.GO -> applyGoFactors(code, lines, depth, loops, factors).also { score += it }
    }

    
    score += applyContextFactors(code, lang, ctx, factors)

    if (lines.size == 1 && cyclomatic <= 1) {
      score = score.coerceAtMost(88)
    }

    val raw = Breakdown(score.coerceIn(1, 99), factors)
    return raw.copy(factors = prioritizeFactors(raw.factors))
  }

  
  fun prioritizeFactors(factors: List<String>): List<String> {
    val neg = factors.filter { it.startsWith("-") }
    val pos = factors.filter { it.startsWith("+") }
    val other = factors.filter { !it.startsWith("-") && !it.startsWith("+") }
    return neg + pos + other
  }

  
  fun topFactors(factors: List<String>, limit: Int = 2): List<String> =
    prioritizeFactors(factors).take(limit.coerceAtLeast(0))

  
  fun applyHysteresis(previous: Int?, raw: Int, band: Int = HYSTERESIS_BAND): Int {
    if (previous == null) return raw
    if (kotlin.math.abs(raw - previous) <= band) return previous
    return raw
  }

  private fun applyContextFactors(
    code: String,
    lang: OptimLang,
    ctx: Context,
    factors: MutableList<String>,
  ): Int {
    var d = 0
    if (isHotPathName(ctx.functionName)) {
      
      d -= 4
      factors += "-hot-path"
    }
    when {
      ctx.fileLineCount >= 2500 -> {
        d -= 5
        factors += "-huge-file"
      }
      ctx.fileLineCount >= 1200 -> {
        d -= 3
        factors += "-large-file"
      }
      ctx.fileLineCount in 1..80 -> {
        d += 2
        
      }
    }
    if (hasIoInBody(code, lang)) {
      d -= 6
      factors += "-io-in-body"
    }
    return d
  }

  private fun hasIoInBody(code: String, lang: OptimLang): Boolean = when (lang) {
    OptimLang.PYTHON -> Regex(
      """\b(open|read|write|readline|readlines|Path\(|\.read_text|\.write_text|requests\.|urllib|httpx|aiohttp)\b""",
    ).containsMatchIn(code)
    OptimLang.JS -> Regex(
      """\b(fetch\s*\(|fs\.|readFile|writeFile|createReadStream|axios\.|XMLHttpRequest)\b""",
    ).containsMatchIn(code)
    OptimLang.GO -> Regex(
      """\b(os\.Open|os\.Create|ioutil\.|io\.Read|http\.(Get|Post|Do)|ListenAndServe)\b""",
    ).containsMatchIn(code)
    OptimLang.C_CPP -> Regex(
      """\b(fopen|fread|fwrite|fclose|printf|scanf|cout|cin|cerr|ofstream|ifstream|fstream)\b""",
    ).containsMatchIn(code)
    else -> Regex(
      """\b(File\s*\(|Files\.|FileInputStream|FileOutputStream|Files\.read|Files\.write|URL\s*\(|HttpURLConnection|OkHttp|openConnection)\b""",
    ).containsMatchIn(code)
  }

  
  private fun applyJvmFactors(
    code: String,
    lines: List<String>,
    depth: Int,
    loops: Int,
    factors: MutableList<String>,
  ): Int {
    var d = 0
    if (Regex("""\bcatch\s*\([^)]*\)\s*\{\s*\}""").containsMatchIn(code)) {
      d -= 14
      factors += "-empty-catch"
    }
    if (Regex("""\bprintStackTrace\s*\(""").containsMatchIn(code)) {
      d -= 10
      factors += "-printStackTrace"
    }
    if (Regex("""\bThread\.sleep\b|\bdelay\s*\(\s*\d{3,}""").containsMatchIn(code)) {
      d -= 12
      factors += "-sleep"
    }
    if (Regex("""!!""").containsMatchIn(code)) {
      d -= 6
      factors += "-not-null-assert"
    }
    if (Regex("""\bas\s+[A-Z]""").findAll(code).count() >
      Regex("""\bas\?""").findAll(code).count()
    ) {
      d -= 5
      factors += "-unsafe-cast"
    }
    if (Regex("""\breturn\b""").containsMatchIn(code) && lines.size in 2..24 && depth <= 2) {
      d += 6
      factors += "+early-return"
    }
    if (Regex("""\bwhen\b|\bswitch\b""").containsMatchIn(code) && depth <= 2) {
      d += 5
      factors += "+structured-branch"
    }
    if (Regex("""\brequire\b|\bcheck\b|\bassert\b|\brequireNotNull\b|\bcheckNotNull\b""")
        .containsMatchIn(code)
    ) {
      d += 4
      factors += "+precondition"
    }
    if (Regex("""\basSequence\b|\bsequence\s*\{|\bSequence<|\.asSequence\(""").containsMatchIn(code)) {
      d += 6
      factors += "+lazy-sequence"
    }
    if (Regex("""\b(map|filter|flatMap|fold|reduce)\s*\{""").containsMatchIn(code) && loops == 0) {
      d += 4
      factors += "+declarative"
    }
    if (Regex("""\b(HashSet|HashMap|LinkedHashSet|mutableSetOf|mutableMapOf|buildSet|buildMap)\b""")
        .containsMatchIn(code) &&
      Regex("""\bcontains\b|\b\[""").containsMatchIn(code)
    ) {
      d += 5
      factors += "+hash-lookup"
    }
    if (Regex("""\buse\s*\{|\buseLines\b|\b.buffered\(""").containsMatchIn(code)) {
      d += 3
      factors += "+resource-use"
    }
    if (Regex("""\bcompanion object\b|\bby lazy\b|\blazy\s*\{""").containsMatchIn(code)) {
      d += 3
      factors += "+lazy-init"
    }
    return d
  }

  private fun applyPythonFactors(
    code: String,
    lines: List<String>,
    depth: Int,
    loops: Int,
    factors: MutableList<String>,
  ): Int {
    var d = 0
    if (Regex("""\bexcept\s*(?:\([^)]*\))?\s*:\s*(?:pass\b|\.\.\.)""").containsMatchIn(code) ||
      Regex("""\bexcept\s*:\s*pass\b""").containsMatchIn(code)
    ) {
      d -= 14
      factors += "-empty-except"
    }
    if (Regex("""\btime\.sleep\b|\basyncio\.sleep\s*\(\s*\d""").containsMatchIn(code)) {
      d -= 12
      factors += "-sleep"
    }
    if (Regex("""\bprint\s*\(""").findAll(code).count() >= 3) {
      d -= 4
      factors += "-debug-print"
    }
    
    if (Regex("""\bfor\b[\s\S]{0,200}?\w+\s*\+=\s*\[""").containsMatchIn(code)) {
      d -= 8
      factors += "-list-concat-loop"
    }
    if (Regex("""\breturn\b""").containsMatchIn(code) && lines.size in 2..24 && depth <= 2) {
      d += 6
      factors += "+early-return"
    }
    if (Regex("""\bmatch\b""").containsMatchIn(code) && depth <= 2) {
      d += 5
      factors += "+structured-branch"
    }
    if (Regex("""\bassert\b""").containsMatchIn(code)) {
      d += 3
      factors += "+precondition"
    }
    
    if (Regex("""\[[^\]]+\bfor\b[^\]]+\]|\([^)]+\bfor\b[^)]+\)|\{[^}]+\bfor\b[^}]+\}""")
        .containsMatchIn(code) && loops <= 1
    ) {
      d += 5
      factors += "+comprehension"
    }
    if (Regex("""\b(set|dict|frozenset)\s*\(|\{\s*[^:]+\}""").containsMatchIn(code) &&
      Regex("""\bin\b""").containsMatchIn(code)
    ) {
      d += 5
      factors += "+hash-lookup"
    }
    if (Regex("""\bwith\s+""").containsMatchIn(code)) {
      d += 3
      factors += "+resource-use"
    }
    if (Regex("""\byield\b|\byield from\b""").containsMatchIn(code)) {
      d += 5
      factors += "+lazy-sequence"
    }
    if (Regex("""\b(map|filter|reduce)\s*\(""").containsMatchIn(code) && loops == 0) {
      d += 3
      factors += "+declarative"
    }
    return d
  }

  private fun applyJsFactors(
    code: String,
    lines: List<String>,
    depth: Int,
    loops: Int,
    factors: MutableList<String>,
  ): Int {
    var d = 0
    if (Regex("""\bcatch\s*\([^)]*\)\s*\{\s*\}""").containsMatchIn(code)) {
      d -= 14
      factors += "-empty-catch"
    }
    if (Regex("""\bconsole\.(log|debug|warn)\s*\(""").findAll(code).count() >= 3) {
      d -= 4
      factors += "-debug-print"
    }
    if (Regex("""\bsetTimeout\s*\(|\bAtomics\.wait\b""").containsMatchIn(code)) {
      d -= 8
      factors += "-sleep"
    }
    if (Regex("""\b==\b""").containsMatchIn(code) && !Regex("""\b===\b""").containsMatchIn(code)) {
      d -= 4
      factors += "-loose-eq"
    }
    if (Regex("""\breturn\b""").containsMatchIn(code) && lines.size in 2..24 && depth <= 2) {
      d += 6
      factors += "+early-return"
    }
    if (Regex("""\bswitch\b""").containsMatchIn(code) && depth <= 2) {
      d += 5
      factors += "+structured-branch"
    }
    if (Regex("""\.(map|filter|flatMap|reduce|forEach)\s*\(""").containsMatchIn(code) && loops == 0) {
      d += 4
      factors += "+declarative"
    }
    if (Regex("""\bnew\s+Set\b|\bnew\s+Map\b|\bWeakMap\b|\bWeakSet\b""").containsMatchIn(code)) {
      d += 5
      factors += "+hash-lookup"
    }
    if (Regex("""\btry\s*\{[\s\S]{0,400}?\bfinally\b|\busing\b""").containsMatchIn(code)) {
      d += 2
      factors += "+resource-use"
    }
    if (Regex("""\basync\s+|\bawait\b""").containsMatchIn(code) &&
      !Regex("""\bawait\s+.*\bawait\b""").containsMatchIn(code)
    ) {
      d += 2
      factors += "+async"
    }
    
    if (Regex("""\bfor\b[\s\S]{0,200}?\bawait\b""").containsMatchIn(code)) {
      d -= 12
      factors += "-await-in-loop"
    }
    return d
  }

  private fun applyGoFactors(
    code: String,
    lines: List<String>,
    depth: Int,
    @Suppress("UNUSED_PARAMETER") loops: Int,
    factors: MutableList<String>,
  ): Int {
    var d = 0
    if (Regex("""\btime\.Sleep\b""").containsMatchIn(code)) {
      d -= 12
      factors += "-sleep"
    }
    if (Regex("""\bfmt\.Print""").findAll(code).count() >= 3) {
      d -= 4
      factors += "-debug-print"
    }
    if (Regex("""\bgo\s+func\b|\bgo\s+\w+""").containsMatchIn(code) &&
      !Regex("""\bsync\.(WaitGroup|Mutex)|errgroup\b""").containsMatchIn(code)
    ) {
      d -= 6
      factors += "-bare-goroutine"
    }
    if (Regex("""\breturn\b""").containsMatchIn(code) && lines.size in 2..24 && depth <= 2) {
      d += 6
      factors += "+early-return"
    }
    if (Regex("""\bswitch\b""").containsMatchIn(code) && depth <= 2) {
      d += 5
      factors += "+structured-branch"
    }
    if (Regex("""\bmap\s*\[""").containsMatchIn(code) || Regex("""\bmake\s*\(\s*map\b""").containsMatchIn(code)) {
      d += 5
      factors += "+hash-lookup"
    }
    if (Regex("""\bdefer\b""").containsMatchIn(code)) {
      d += 3
      factors += "+resource-use"
    }
    if (Regex("""\berrors\.Is\b|\berrors\.As\b|\bfmt\.Errorf\b""").containsMatchIn(code)) {
      d += 3
      factors += "+precondition"
    }
    
    return d
  }

  
  private fun stripComments(body: String, lang: OptimLang): String = when (lang) {
    OptimLang.PYTHON -> body
      .replace(Regex("""#.*?$""", RegexOption.MULTILINE), " ")
      .replace(Regex("(?s)'''.*?'''|\"\"\".*?\"\"\""), " ")
    else -> body.replace(Regex("""//.*?$|/\*[\s\S]*?\*/""", RegexOption.MULTILINE), " ")
  }

  private fun nestingDepth(code: String, lang: OptimLang): Int = when (lang) {
    OptimLang.PYTHON -> maxIndentDepth(code)
    else -> maxBraceDepth(code).coerceAtLeast(if (lang == OptimLang.GENERIC) maxIndentDepth(code) else 0)
  }

  private fun maxIndentDepth(text: String): Int {
    val lines = text.lines().filter { it.isNotBlank() }
    if (lines.isEmpty()) return 0
    val indents = lines.map { line ->
      var i = 0
      var spaces = 0
      while (i < line.length) {
        when (line[i]) {
          ' ' -> {
            spaces++
            i++
          }
          '\t' -> {
            spaces += 4
            i++
          }
          else -> break
        }
      }
      spaces
    }
    val base = indents.first()
    
    val diffs = indents.zipWithNext()
      .map { (a, b) -> kotlin.math.abs(b - a) }
      .filter { it in 1..8 }
    val unit = when {
      diffs.any { it % 4 == 0 } && diffs.none { it == 2 || it == 3 } -> 4
      diffs.any { it % 2 == 0 } -> 2
      else -> 4
    }
    val relative = indents.map { ((it - base) / unit).coerceAtLeast(0) }
    
    var suiteDepth = 0
    var maxSuite = 0
    for ((idx, line) in lines.withIndex()) {
      val level = relative.getOrElse(idx) { 0 }
      if (level > maxSuite) maxSuite = level
      val trimmed = line.trimEnd()
      if (trimmed.endsWith(":") && !trimmed.trimStart().startsWith("#")) {
        suiteDepth = level + 1
        if (suiteDepth > maxSuite) maxSuite = suiteDepth
      }
    }
    return maxOf(relative.maxOrNull() ?: 0, maxSuite)
  }

  private fun maxBraceDepth(text: String): Int {
    var depth = 0
    var max = 0
    for (c in text) {
      when (c) {
        '{' -> {
          depth++
          if (depth > max) max = depth
        }
        '}' -> if (depth > 0) depth--
      }
    }
    return max
  }

  private fun estimateCyclomatic(code: String): Int {
    val keys = Regex(
      """\b(if|else if|elif|when|case|for|while|do|catch|except|guard|switch|\?\.|&&|\|\||\?\:)\b""",
    )
    return 1 + keys.findAll(code).count()
  }

  private fun countLoops(code: String, lang: OptimLang): Int = when (lang) {
    OptimLang.PYTHON -> {
      
      val all = Regex("""\b(for|while)\b""").findAll(code).count()
      val inComp = Regex(
        """[\[({][^\]})]*\bfor\b[^\]})]*[\])}]""",
      ).findAll(code).sumOf { Regex("""\bfor\b""").findAll(it.value).count() }
      (all - inComp).coerceAtLeast(0)
    }
    OptimLang.GO -> Regex("""\b(for)\b""").findAll(code).count()
    OptimLang.JS -> Regex(
      """\b(for|while|do)\b|\.(forEach|map|filter|reduce|flatMap)\s*\(""",
    ).findAll(code).count()
    else -> Regex("""\b(for|while|do)\b""").findAll(code).count()
  }

  private fun hasNestedLoops(code: String, lang: OptimLang): Boolean = when (lang) {
    OptimLang.PYTHON -> pythonHasNestedLoops(code)
    OptimLang.JS -> Regex(
      """\b(for|while|do)\b[\s\S]{0,240}?\{[\s\S]{0,500}?\b(for|while|do)\b""" +
        """|\.(forEach|map)\s*\([\s\S]{0,200}?\.(forEach|map)\s*\(""",
    ).containsMatchIn(code)
    else -> Regex(
      """\b(for|while)\b[\s\S]{0,240}?\{[\s\S]{0,400}?\b(for|while)\b""",
    ).containsMatchIn(code)
  }

  
  private fun pythonHasNestedLoops(code: String): Boolean {
    data class Loop(val indent: Int)
    val stack = ArrayDeque<Loop>()
    for (raw in code.lines()) {
      if (raw.isBlank() || raw.trimStart().startsWith("#")) continue
      var spaces = 0
      var i = 0
      while (i < raw.length) {
        when (raw[i]) {
          ' ' -> {
            spaces++
            i++
          }
          '\t' -> {
            spaces += 4
            i++
          }
          else -> break
        }
      }
      val trimmed = raw.trimStart()
      
      if (Regex("""^[\[({].*\bfor\b.*[\])}]\s*$""").matches(trimmed)) continue
      while (stack.isNotEmpty() && stack.last().indent >= spaces) stack.removeLast()
      if (Regex("""^(async\s+)?(for|while)\b""").containsMatchIn(trimmed)) {
        if (stack.isNotEmpty()) return true
        stack.addLast(Loop(spaces))
      }
    }
    return false
  }

  private fun allocInLoop(code: String, lang: OptimLang): Boolean {
    val alloc = when (lang) {
      OptimLang.PYTHON -> Regex("""\b(list|dict|set|bytearray)\s*\(|\[\s*\]|\{\s*\}""")
      OptimLang.JS -> Regex("""\bnew\s+(Array|Object|Map|Set|WeakMap)\b|\[\s*\]|\{\s*\}""")
      OptimLang.GO -> Regex("""\bmake\s*\(|\bnew\s*\(""")
      OptimLang.C_CPP -> Regex("""\b(malloc|calloc|realloc|new)\s*[\*(]|\bstd::(vector|string|map|unordered_map)\b""")
      else -> Regex(
        """\b(ArrayList|HashMap|HashSet|mutableListOf|mutableMapOf|mutableSetOf|arrayListOf|StringBuilder|ByteArray|IntArray)\s*[({]""",
      )
    }
    return loopBlocks(code, lang).any { alloc.containsMatchIn(it) }
  }

  private fun stringConcatInLoop(code: String, lang: OptimLang): Boolean {
    val concat = when (lang) {
      OptimLang.PYTHON -> Regex("""\b\w+\s*\+=\s*["']|\b\w+\s*=\s*\w+\s*\+\s*["']""")
      OptimLang.JS -> Regex("""\b\w+\s*\+=\s*["'`]|\b\w+\s*=\s*\w+\s*\+\s*["'`]""")
      OptimLang.GO -> Regex("""\b\w+\s*\+=\s*"|\b\w+\s*=\s*\w+\s*\+\s*"""")
      else -> Regex("""\b\w+\s*\+=\s*["']|\b\w+\s*=\s*\w+\s*\+\s*["']""")
    }
    return loopBlocks(code, lang).any { concat.containsMatchIn(it) }
  }

  private fun sortInHotPath(code: String, lang: OptimLang): Boolean = when (lang) {
    OptimLang.PYTHON -> Regex("""\b\.sort\s*\(|\bsorted\s*\(""").containsMatchIn(code)
    OptimLang.JS -> Regex("""\b\.sort\s*\(""").containsMatchIn(code)
    OptimLang.GO -> Regex("""\bsort\.""").containsMatchIn(code)
    else -> Regex("""\bCollections\.sort\b|\b\.sorted\(\)|\b\.sortedBy\b""").containsMatchIn(code)
  }

  private fun linearLookupInLoop(code: String, lang: OptimLang): Boolean = when (lang) {
    OptimLang.PYTHON ->
      Regex("""\bin\b""").containsMatchIn(code) &&
        Regex("""\b(list|\[)""").containsMatchIn(code)
    OptimLang.JS ->
      Regex("""\b\.includes\s*\(|\b\.indexOf\s*\(""").containsMatchIn(code)
    OptimLang.GO -> false // maps are default
    else ->
      Regex("""\bcontains\s*\(""").containsMatchIn(code) &&
        Regex("""\b(List|ArrayList|MutableList)\b""").containsMatchIn(code)
  }

  private fun loopBlocks(code: String, lang: OptimLang): Sequence<String> = when (lang) {
    OptimLang.PYTHON -> pythonLoopBlocks(code)
    OptimLang.JS -> {
      val brace = Regex(
        """\b(for|while|do)\b[\s\S]{0,80}?\{([\s\S]{0,500}?)\}""",
      ).findAll(code).map { m -> m.groupValues.getOrElse(2) { m.value } }
      val each = Regex(
        """\.(forEach|map|filter|reduce)\s*\(([\s\S]{0,400}?)\)""",
      ).findAll(code).map { m -> m.groupValues.getOrElse(2) { m.value } }
      brace + each
    }
    else -> Regex(
      """\b(for|while)\b[\s\S]{0,80}?\{([\s\S]{0,500}?)\}""",
    ).findAll(code).map { m -> m.groupValues.getOrElse(2) { m.value } }
  }

  private fun pythonLoopBlocks(code: String): Sequence<String> = sequence {
    val lines = code.lines()
    var idx = 0
    while (idx < lines.size) {
      val raw = lines[idx]
      val trimmed = raw.trimStart()
      if (!Regex("""^(async\s+)?(for|while)\b""").containsMatchIn(trimmed)) {
        idx++
        continue
      }
      if (Regex("""^[\[({].*\bfor\b""").containsMatchIn(trimmed)) {
        idx++
        continue
      }
      val headerIndent = raw.length - trimmed.length
      val body = StringBuilder()
      var j = idx + 1
      while (j < lines.size) {
        val l = lines[j]
        if (l.isBlank()) {
          body.appendLine(l)
          j++
          continue
        }
        val ind = l.length - l.trimStart().length
        if (ind <= headerIndent) break
        body.appendLine(l)
        j++
      }
      if (body.isNotEmpty()) yield(body.toString())
      idx = j.coerceAtLeast(idx + 1)
    }
  }

  private fun isHotPathName(raw: String): Boolean {
    val name = raw.trim()
    if (name.isEmpty()) return false
    if (HOT_PATH_NAMES.any { it.equals(name, ignoreCase = true) }) return true
    return HOT_PATH_NAMES.any { base ->
      name.startsWith(base, ignoreCase = true) &&
        (name.endsWith("Handler", ignoreCase = true) ||
          name.endsWith("Listener", ignoreCase = true) ||
          name.endsWith("Callback", ignoreCase = true) ||
          name.endsWith("Action", ignoreCase = true) ||
          name.endsWith("Request", ignoreCase = true))
    }
  }

  private const val HYSTERESIS_BAND = 3

  private val HOT_PATH_NAMES = listOf(
    "main", "render", "handle", "onCreate", "onClick", "paint", "draw",
    "update", "tick", "frame", "process", "serve", "invoke", "run",
    "execute", "dispatch", "callback", "listener",
  )
}
