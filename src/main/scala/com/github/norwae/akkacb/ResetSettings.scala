package com.github.norwae.akkacb

import scala.concurrent.duration.{Duration, FiniteDuration}

/**
  * Determine how a opened breaker should attempt to reset itself.
  * @param initialResetDuration wait time for first reset attempt
  * @param maximumResetDuration maximum duration between two attempts
  * @param backoffFactor increment between two attempts, must be >= 1
  */
case class ResetSettings(initialResetDuration: FiniteDuration, maximumResetDuration: Duration, backoffFactor: Double) {
  require(backoffFactor >= 1)
}
