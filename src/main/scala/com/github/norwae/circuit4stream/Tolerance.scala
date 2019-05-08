package com.github.norwae.circuit4stream

import java.time.Instant

import scala.concurrent.duration.FiniteDuration
import scala.util.Try

/**
  * decides if the circuit breaker should open after a result has
  * been received. A Tolerance implementation may provide an
  * "event log", which creates history between implementations
  * without depending on mutable state.
  *
  * The Tolerance implementation may inspect both successful
  * and failed elements - and has the ability to trigger on
  *
  * @tparam A type of elements produced
  */
trait Tolerance[-A] {
  /** Event log type. Usually some kind of collection.
    * The implementation of [[apply()]] should perform any housekeeping
    * required to avoid it growing without bound.
    */
  type EventLog

  /**
    * Initial value of the event log
    *
    * @return initial value
    */
  def initialLog: EventLog

  /**
    * Evaluates an event log to determine if the circuit should
    * open given this recent history.
    *
    * @param events event log
    * @param next   next event
    * @return pair: an updated event log for future invocations, boolean indicating if the circuit should open
    */
  def apply(events: EventLog, next: Try[A]): (EventLog, Boolean)
}

object Tolerance {

  case class FailureFraction(toleratedFraction: Double, per: FiniteDuration) extends Tolerance[Any] {

    final class Event(val time: Instant, val failed: Boolean)

    override type EventLog = Vector[Event]

    override def initialLog: EventLog = Vector.empty

    override def apply(events: EventLog, next: Try[Any]): (EventLog, Boolean) = {
      val cutoff = Instant.now().minusMillis(per.toMillis)
      val relevantEvents = events.filter(_.time isAfter cutoff) :+ new Event(Instant.now(), next.isFailure)

      val (good, bad) = relevantEvents.foldLeft((0, 0)) { (p, event) =>
        val (successes, failures) = p

        if (event.failed) (successes, failures + 1)
        else (successes + 1, failures)
      }

      val failedFraction = bad.toDouble / (good + bad)

      (relevantEvents, failedFraction > toleratedFraction)
    }
  }

  case class FailureFrequency(incidents: Int, per: FiniteDuration) extends Tolerance[Any] {
    type EventLog = Vector[Instant]

    override def initialLog: EventLog = Vector.empty

    override def apply(events: EventLog, next: Try[Any]): (EventLog, Boolean) = {
      val cutoff = Instant.now().minusMillis(per.toMillis)
      val relevantEvents = events.filter(_.isAfter(cutoff))
      val nextLog =
        if (next.isFailure) relevantEvents :+ Instant.now()
        else relevantEvents

      (nextLog, nextLog.length >= incidents)
    }
  }

}