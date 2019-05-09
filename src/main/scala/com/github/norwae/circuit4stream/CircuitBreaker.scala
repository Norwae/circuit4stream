package com.github.norwae.circuit4stream

import akka.stream.scaladsl.{BidiFlow, Flow, FlowOps, Keep}

import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success, Try}
import scala.util.control.NonFatal


/**
  * builders and convenience functions to work with the circuit breaker
  * stage.
  */
object CircuitBreaker {
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
    * @define sorryForEC Unfortunately, this operation will require an execution context, since the parasitic execution context introduced in scala 2.13 is not yet available
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
