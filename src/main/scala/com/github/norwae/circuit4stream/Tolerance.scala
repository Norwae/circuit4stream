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
    * The implementation of [[Tolerance.apply()]] should perform any housekeeping
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

  /**
    * Tolerates failures (within a certain timeframe) up to a fraction of total
    * seen elements. The event log will create a single object for each event within
    * the evaluated time frame, but not retain a reference to the result. If there were fewer elements
    * in the timeframe than some defined minimum, the breaker will stay closed, in order to avoid an initial
    * failure triggering the breaker immediately
    *
    * @param toleratedFraction fraction of elements that may fail. Inclusive
    * @param per               timeframe to evaluate
    * @param minimumEvents     minimum of events to evaluate the condition on
    */
  case class FailureFraction(toleratedFraction: Double, per: FiniteDuration, minimumEvents: Int = 1) extends Tolerance[Any] {
    require(toleratedFraction <= 1.0)

    final class Event(val time: Instant, val failed: Boolean)

    override type EventLog = Vector[Event]

    override def initialLog: EventLog = Vector.empty

    override def apply(events: EventLog, next: Try[Any]): (EventLog, Boolean) = {
      val cutoff = Instant.now().minusMillis(per.toMillis)
      val relevantEvents = events.filter(_.time isAfter cutoff) :+ new Event(Instant.now(), next.isFailure)

      if (relevantEvents.length < minimumEvents) {
        (relevantEvents, false)
      } else {
        val bad = relevantEvents.count(_.failed)

        val failedFraction = bad.toDouble / relevantEvents.length

        (relevantEvents, failedFraction > toleratedFraction)
      }
    }
  }

  /**
    * Tolerates up to a total number of events for a given timeframe. The
    * event log will create an object for each failure encountered during the
    * timeframe.
    * @param incidents nr of incidents after which the breaker should open
    * @param per duration to evaluate
    */
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