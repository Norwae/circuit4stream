package com.github.norwae.akkacb

/**
  * The mode for a circuit breaker - the breaker can either
  * bypass the failing component (for example to relieve
  * pressure on an other system), or backpressure the stream
  */
sealed trait CircuitBreakerMode
object CircuitBreakerMode {

  /** Select backpressure mode - the circuit breaker will
    * backpressure the stream while open, not request
    * new elements from upstream until it closes
    */
  case object Backpressure extends CircuitBreakerMode

  /**
    * Select bypass mode - the circuit breaker will request
    * an upstream event immediately, pushing a failure
    * with a [[CircuitBreakerIsOpen]] exception
    * downstream instead of stressing the failing component
    */
  case object Bypass extends CircuitBreakerMode

  /**
    * Unique exception object to indicate the circuit breaker
    * is currently open
    */
  case object CircuitBreakerIsOpen extends Exception("The circuit breaker is currently open", null, false, false)
}
