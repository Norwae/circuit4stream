package com.github.norwae.akkacb

/**
  * Defines the parameters for a circuit breaker stage
  * @param tolerance tolerance setting
  * @param resetSettings reset settings
  * @param mode mode
  * @tparam A "output" type of the circuit breaker (Required to select a proper tolerance)
  */
case class CircuitBreakerSettings[A](tolerance: Tolerance[A],
                                     resetSettings: ResetSettings,
                                     mode: CircuitBreakerMode = CircuitBreakerMode.Backpressure)