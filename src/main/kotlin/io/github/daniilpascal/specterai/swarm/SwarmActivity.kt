package io.github.daniilpascal.specterai.swarm

import java.util.concurrent.atomic.AtomicInteger


object SwarmActivity {
  private val depth = AtomicInteger(0)

  fun begin() {
    depth.incrementAndGet()
  }

  fun end() {
    depth.updateAndGet { (it - 1).coerceAtLeast(0) }
  }

  val isActive: Boolean
    get() = depth.get() > 0
}
