package io.github.daniilpascal.specterai.optim

import io.github.daniilpascal.specterai.optim.FunctionOptimScanner
import io.github.daniilpascal.specterai.optim.FunctionOptimScore
import io.github.daniilpascal.specterai.optim.OptimLang
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test


class FunctionOptimScanTest {

  @Test
  fun kotlin_expressionBody_isScored() {
    val src = """
      fun add(a: Int, b: Int) = a + b
      
      fun empty() {
      }
    """.trimIndent()
    val hits = FunctionOptimScanner.scan(src, OptimLang.JVM)
    val add = hits.find { it.name == "add" }
    assertNotNull(add)
    assertNotNull(add!!.optim)
    assertTrue(add.optim!!.percent in 1..99)

    val empty = hits.find { it.name == "empty" }
    
    if (empty != null) {
      assertNull(empty.optim)
    }
  }

  @Test
  fun python_def_indentBody() {
    val src = """
      def greet(name):
          return f"hi {name}"
      
      def noop():
          pass
    """.trimIndent()
    val hits = FunctionOptimScanner.scan(src, OptimLang.PYTHON)
    val greet = hits.find { it.name == "greet" }
    assertNotNull(greet)
    assertNotNull(greet!!.optim)
    val noop = hits.find { it.name == "noop" }
    if (noop != null) {
      assertNull(noop.optim)
    }
  }

  @Test
  fun js_arrow_and_function() {
    val src = """
      function legacy(x) {
        return x * 2;
      }
      
      const modern = (x) => x + 1;
    """.trimIndent()
    val hits = FunctionOptimScanner.scan(src, OptimLang.JS)
    assertTrue(hits.any { it.name == "legacy" && it.optim != null })
    assertTrue(hits.any { it.name == "modern" && it.optim != null })
  }

  @Test
  fun go_func_scanned() {
    val src = """
      func Sum(a, b int) int {
      	return a + b
      }
    """.trimIndent()
    val hits = FunctionOptimScanner.scan(src, OptimLang.GO)
    assertEquals(1, hits.size)
    assertEquals("Sum", hits[0].name)
    assertNotNull(hits[0].optim)
  }

  @Test
  fun c_cpp_func_scanned() {
    val src = """
      int add(int a, int b) {
        return a + b;
      }

      void empty_stub() {
      }

      int proto_only(int x);
    """.trimIndent()
    val hits = FunctionOptimScanner.scan(src, OptimLang.C_CPP)
    val add = hits.find { it.name == "add" }
    assertNotNull(add)
    assertNotNull(add!!.optim)
    val empty = hits.find { it.name == "empty_stub" }
    if (empty != null) {
      assertNull(empty.optim)
    }
    assertTrue(hits.none { it.name == "proto_only" })
  }

  @Test
  fun score_nestedLoops_penalized() {
    val body = """
      for (i in 0..10) {
        for (j in 0..10) {
          println(i + j)
        }
      }
    """.trimIndent()
    val good = FunctionOptimScore.analyze("return 1", OptimLang.JVM)
    val bad = FunctionOptimScore.analyze(body, OptimLang.JVM)
    assertTrue(bad.percent < good.percent)
    assertTrue(bad.factors.any { it.contains("nested") || it.contains("loop") })
  }

  @Test
  fun score_python_comprehension_bonus() {
    val body = """
      return [x * 2 for x in items if x > 0]
    """.trimIndent()
    val b = FunctionOptimScore.analyze(body, OptimLang.PYTHON)
    assertTrue(b.factors.contains("+comprehension") || b.percent >= 60)
  }

  @Test
  fun stepB_hotPath_and_io_factors() {
    val body = """
      val data = Files.readAllBytes(path)
      return data.size
    """.trimIndent()
    val b = FunctionOptimScore.analyze(
      body,
      OptimLang.JVM,
      FunctionOptimScore.Context(functionName = "main", fileLineCount = 1500),
    )
    assertTrue(b.factors.contains("-hot-path"))
    assertTrue(b.factors.contains("-io-in-body") || b.factors.contains("-large-file"))
  }

  @Test
  fun stepB_hysteresis_holds_small_jumps() {
    assertEquals(70, FunctionOptimScore.applyHysteresis(70, 72))
    assertEquals(70, FunctionOptimScore.applyHysteresis(70, 67))
    assertEquals(75, FunctionOptimScore.applyHysteresis(70, 75))
    assertEquals(50, FunctionOptimScore.applyHysteresis(null, 50))
  }

  @Test
  fun stepB_topFactors_prefers_penalties() {
    val top = FunctionOptimScore.topFactors(
      listOf("+flat", "-nested-loops", "+short", "-io-in-body"),
      limit = 2,
    )
    assertEquals(2, top.size)
    assertTrue(top.all { it.startsWith("-") })
  }

  @Test
  fun stepB_python_nested_indent_loops() {
    val body = """
      for i in items:
          for j in i:
              total += j
    """.trimIndent()
    val b = FunctionOptimScore.analyze(body, OptimLang.PYTHON)
    assertTrue(b.factors.any { it.contains("nested") || it.contains("loop") })
  }
}
