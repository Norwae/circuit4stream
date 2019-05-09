package com.github.norwae.circuit4stream

import java.time.Instant

import akka.stream.scaladsl.{BidiFlow, Flow, FlowOps, Keep}
import akka.stream.stage._
import akka.stream.{Attributes, BidiShape, Inlet, Outlet}

import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}
import scala.util.control.NonFatal
import scala.util.{Failure, Success, Try}

object CircuitBreakerStage {
  /**
    * Wraps a potentially-failing flow with a circuit breaker.
    *
    * @param settings Circuit breaker configuration
    * @param flow     flow to be wrapped
    * @tparam A   input type
    * @tparam B   output type
    * @tparam Mat materialized value type
    * @return wrapped flow
    */
  def apply[A, B, Mat](settings: CircuitBreakerSettings[B], flow: Flow[A, Try[B], Mat]): Flow[A, Try[B], Mat] = {
    val bidi = BidiFlow.fromGraph(new CircuitBreakerStage[A, B](settings))
    bidi.joinMat(flow)(Keep.right)
  }

  /**
    *
    * Adds operations to map async operations to flows
    * @define sorryForEc Unfortunately, this operation will require an execution context, since the parasitic execution context introduced in scala 2.13 is not yet available
    * @param fo flow
    * @tparam X input
    * @tparam A intermediate output
    * @tparam M materialized value
    */
  implicit class FlowOpsPimp[X, A, M](val fo: Flow[X, A, M]) extends AnyVal {
    private def adaptOperator[B](f: A => Future[B])(implicit ec: ExecutionContext) = {
      f.andThen { result =>
        result.map(Success.apply).recover {
          case NonFatal(e) => Failure(e)
        }
      }
    }

    /**
      * Variant of [[FlowOps.mapAsync()]] which does not map
      * future failures to stream failures, but instead use the
      * `Try` monad to capture them.
      *
      * $sorryForEC
      *
      * @param parallelism nr of parallel invocactions
      * @param f operation
      * @param ec execution context
      * @tparam B result type
      * @return adapted flow
      */
    def mapAsyncRecover[B](parallelism: Int)(f: A => Future[B])(implicit ec: ExecutionContext): Flow[X, Try[B], M] = {
      fo.mapAsync(parallelism)(adaptOperator(f))
    }

    /**
      * Variant of [[FlowOps.mapAsyncUnordered()]] which does not map
      * future failures to stream failures, but instead use the
      * `Try` monad to capture them.
      *
      * $sorryForEC
      *
      * @param parallelism nr of parallel invocactions
      * @param f operation
      * @param ec execution context
      * @tparam B result type
      * @return adapted flow
      */
    def mapAsyncUnorderedRecover[B](parallelism: Int)(f: A => Future[B])(implicit ec: ExecutionContext): Flow[X, Try[B], M] = {
      fo.mapAsyncUnordered(parallelism)(adaptOperator(f))
    }
  }
}

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
          val escalated = resetDuration * settings.resetSettings.backoffFactor
          val nextAttempt = (escalated max settings.resetSettings.maximumResetDuration).asInstanceOf[FiniteDuration]
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
