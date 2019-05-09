package com.github.norwae.circuit4stream

import scala.concurrent.duration.{Duration, FiniteDuration}

/**
  * Determine how a opened breaker should attempt to reset itself.
  *
  * @param initialResetDuration wait time for first reset attempt
  * @param maximumResetDuration maximum duration between two attempts
  * @param backoffFactor        increment between two attempts, must be >= 1
  */
case class ResetSettings(initialResetDuration: FiniteDuration, maximumResetDuration: Duration = Duration.Inf, backoffFactor: Double = 2) {
  require(backoffFactor >= 1)
}
