package io.github.daniilpascal.specterai.ai

import io.github.daniilpascal.specterai.settings.AiSettings


object TypingSpeed {
  const val MIN = 1
  const val MAX = 100

  
  private const val SLOW_DELAY_MS = 80
  private const val FAST_DELAY_MS = 6

  data class Profile(
    val speed: Int?,
    val delayMs: Int,
    val charsPerTick: Int,
  )

  fun resolve(speedArg: Int?): Profile {
    if (speedArg == null) {
      val delay = AiSettings.getInstance().typingDelayMs
        .coerceIn(AiSettings.MIN_TYPING_DELAY_MS, AiSettings.MAX_TYPING_DELAY_MS)
      
      return Profile(speed = null, delayMs = delay, charsPerTick = 12)
    }

    val speed = speedArg.coerceIn(MIN, MAX)
    
    val t = (speed - MIN).toDouble() / (MAX - MIN).toDouble()
    val delay = (SLOW_DELAY_MS - (SLOW_DELAY_MS - FAST_DELAY_MS) * t).toInt()
      .coerceIn(FAST_DELAY_MS, SLOW_DELAY_MS)
    val chars = when {
      speed >= 85 -> 24
      speed >= 65 -> 16
      speed >= 40 -> 12
      speed >= 20 -> 8
      else -> 4
    }
    return Profile(speed = speed, delayMs = delay, charsPerTick = chars)
  }
}
