package com.github.norwae.circuit4stream

import java.time.Instant

import akka.stream.stage._
import akka.stream.{Attributes, BidiShape, Inlet, Outlet}

import scala.concurrent.duration._
import scala.util.{Failure, Try}

/**
  * bidirectional stage that can wrap a "flow" and perform circuit-breaker operations on it, guiding
  * its input and output handling
  * @param settings settings for this circuit breaker
  * @tparam In input type
  * @tparam Out output type
  */

class CircuitBreakerStage[In, Out](settings: CircuitBreakerSettings[Out]) extends GraphStage[BidiShape[In, In, Try[Out], Try[Out]]] {
  private val in = Inlet[In]("main.in")
  private val fwdOut = Outlet[In]("fwd.out")
  private val fwdIn = Inlet[Try[Out]]("fwd.in")
  private val out = Outlet[Try[Out]]("main.out")

  override def shape: BidiShape[In, In, Try[Out], Try[Out]] = BidiShape(in, fwdOut, fwdIn, out)

  override def createLogic(inheritedAttributes: Attributes): GraphStageLogic = {
    settings.mode match {
      case CircuitBreakerMode.Bypass => new BypassLogic()
      case CircuitBreakerMode.Backpressure => new BackpressureLogic
    }
  }

  // visible for testing
  private [circuit4stream] def calculateNextDelay(current: FiniteDuration): FiniteDuration = {
    val escalated = current * settings.resetSettings.backoffFactor
    val max = settings.resetSettings.maximumResetDuration

    val nextDelay =
      if (max.isFinite() && max < escalated) max
      else escalated

    nextDelay.asInstanceOf[FiniteDuration]
  }

  private abstract class BaseLogic extends GraphStageLogic(shape) with StageLogging {
    private val asyncBecomeHalfOpen = getAsyncCallback(becomeHalfOpen)

    protected var events: settings.tolerance.EventLog = settings.tolerance.initialLog

    private object defaultInHandler extends InHandler {
      override def onPush(): Unit = push(fwdOut, grab(in))

      override def onUpstreamFinish(): Unit = complete(fwdOut)
    }

    private object defaultOutHandler extends OutHandler {
      override def onPull(): Unit = pull(fwdIn)
    }

    private[CircuitBreakerStage] object defaultResultFwdHandler extends InHandler {
      override def onPush(): Unit = {
        val computed = grab(fwdIn)
        val (filteredEvents, open) = settings.tolerance.apply(events, computed)
        events = filteredEvents

        push(out, computed)

        if (open) {
          val initial = settings.resetSettings.initialResetDuration
          onBreakerTripped(Instant.now().plusMillis(initial.toMillis))
          log.info(s"Tripped circuit breaker, will attempt to recover at $initial")
          materializer.scheduleOnce(initial, () => asyncBecomeHalfOpen.invoke(initial))
        }
      }
    }

    setHandler(fwdIn, defaultResultFwdHandler)
    setHandler(fwdOut, () => if (!hasBeenPulled(in)) tryPull(in))
    setHandler(in, defaultInHandler)
    setHandler(out, defaultOutHandler)

    private def becomeHalfOpen(resetDuration: FiniteDuration): Unit = {
      log.debug("Circuit breaker entering half-open state")
      pull(fwdIn)

      val previousHandler = getHandler(in)
      setHandler(in, () => {
        setHandler(in, previousHandler)
        push(fwdOut, grab(in))
      })
      setHandler(fwdIn, () => {
        val result = grab(fwdIn)
        emit(out, result)

        if (result.isSuccess) onBreakerClosed()
        else {
          val nextAttempt = calculateNextDelay(resetDuration)
          log.info(s"Circuit breaker could not recover, will retry at $nextAttempt")

          materializer.scheduleOnce(nextAttempt, () => asyncBecomeHalfOpen.invoke(nextAttempt))
          onBreakerTripped(Instant.now().plusMillis(nextAttempt.toMillis))
        }
      })
    }

    def onBreakerTripped(nextReset: Instant): Unit

    def onBreakerClosed(): Unit = {
      log.info("Circuit breaker recovered")
      setHandler(out, defaultOutHandler)
      setHandler(in, defaultInHandler)
      setHandler(fwdIn, defaultResultFwdHandler)
    }
  }

  private class BypassLogic extends BaseLogic {
    override def onBreakerTripped(nextReset: Instant): Unit = {
      setHandler(fwdIn, defaultResultFwdHandler)
      setHandler(out, () => pull(in))
      setHandler(in, () => {
        val discarded = grab(in)
        log.debug("Discarding a message for bypass mode")
        push(out, Failure(CircuitBreakerMode.CircuitBreakerIsOpen(discarded, nextReset)))
      })
    }
  }

  private class BackpressureLogic extends BaseLogic {
    override def onBreakerTripped(nextReset: Instant): Unit = {
      setHandler(fwdIn, defaultResultFwdHandler)
      // mute the pull on the output
      setHandler(out, GraphStageLogic.EagerTerminateOutput)
    }
  }

}
