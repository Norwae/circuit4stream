package com.github.norwae.circuit4stream

import java.io.IOException

import akka.NotUsed
import akka.actor.ActorSystem
import akka.stream.scaladsl.{BidiFlow, Flow, Sink, Source}
import akka.stream.testkit.TestSubscriber
import akka.stream.testkit.scaladsl.StreamTestKit.assertAllStagesStopped
import akka.stream.{ActorMaterializer, Materializer}
import com.typesafe.config.{Config, ConfigFactory}
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.{Matchers, WordSpec}

import scala.concurrent.Future
import scala.concurrent.duration._
import scala.util.{Failure, Random, Success, Try}

class CircuitBreakerStageTest extends WordSpec with Matchers with ScalaFutures {
  private val config: Config =
    ConfigFactory.
      parseString("akka.stream.materializer.debug.fuzzing-mode = on").
      withFallback(ConfigFactory.load())

  implicit lazy val system: ActorSystem = ActorSystem("test", config)
  implicit lazy val materializer: Materializer = ActorMaterializer()
  implicit lazy val ec = materializer.executionContext

  private val expectedFailure = Failure(new UnsupportedOperationException())
  private val alwaysSucceed = Flow.fromFunction(Success.apply[Int])
  private val alwaysFail = Flow.fromFunction((_: Any) => expectedFailure)
  private val defaultSettings =
    CircuitBreakerSettings[Int](Tolerance.FailureFrequency(3, 1.second), ResetSettings(1.second), CircuitBreakerMode.Backpressure)

  "The pimped circuit breaker syntax" must {
    import CircuitBreaker._
    "provide syntactic sugar for mapAsync" in {
      val flow: Flow[Int, Try[Int], NotUsed] = Flow[Int].mapAsyncRecover(1)(Future.successful)
      val src = Source.single(1).via(flow)
      whenReady(src.runWith(Sink.head[Try[Int]])) { it =>
        it shouldEqual Success(1)
      }
    }

    "provide syntactic sugar for mapAsyncUnordered" in {
      val flow: Flow[Int, Try[Int], NotUsed] = Flow[Int].mapAsyncUnorderedRecover(1)(Future.successful)
      val src = Source.single(1).via(flow)
      whenReady(src.runWith(Sink.head[Try[Int]])) { it =>
        it shouldEqual Success(1)
      }
    }

    "be relatively readable in the linear DSL" in {
      val rawFlow = Flow[Int].mapAsyncRecover(1)(_ => Future.failed(new IOException()))
      val flow = CircuitBreaker(defaultSettings, rawFlow)

      val result = Source.single(1).
        via(flow).
        runWith(Sink.head[Try[Int]])

      whenReady(result) {
        _ should matchPattern {
          case Failure(_ : IOException) =>
        }
      }
    }
  }

  "A circuit breaker" when {
    "operating normally" should {
      "shut down cleanly with no elements" in assertAllStagesStopped {
        val probe = TestSubscriber.manualProbe[Try[Int]]()
        val sut = BidiFlow.fromGraph(new CircuitBreakerStage[Int, Int](defaultSettings))

        val flow = sut.join(alwaysSucceed)
        Source.empty.via(flow).runWith(Sink.fromSubscriber(probe))

        probe.expectSubscription()
        probe.expectComplete()
      }

      "shut down cleanly with no demand" in assertAllStagesStopped {
        val probe = TestSubscriber.manualProbe[Try[Int]]()
        val sut = BidiFlow.fromGraph(new CircuitBreakerStage[Int, Int](defaultSettings))

        val flow = sut.join(alwaysSucceed)
        Source.repeat(1).via(flow).runWith(Sink.fromSubscriber(probe))

        val subscription = probe.expectSubscription()
        subscription.cancel()
      }

      "pass all elements through successfully" in assertAllStagesStopped {
        val probe = TestSubscriber.manualProbe[Try[Int]]()
        val sut = BidiFlow.fromGraph(new CircuitBreakerStage[Int, Int](defaultSettings))

        val flow = sut.join(alwaysSucceed)
        Source.repeat(1).via(flow).runWith(Sink.fromSubscriber(probe))

        val subscription = probe.expectSubscription()
        for (_ <- 0 until 10000) {
          subscription.request(1)
          probe.expectNext(Success(1))
        }

        subscription.cancel()
      }

      "terminate cleanly if the source is exhausted" in assertAllStagesStopped {
        val probe = TestSubscriber.manualProbe[Try[Int]]()
        val sut = BidiFlow.fromGraph(new CircuitBreakerStage[Int, Int](defaultSettings))

        val flow = sut.join(alwaysSucceed)
        Source.repeat(1).take(10).via(flow).runWith(Sink.fromSubscriber(probe))

        val subscription = probe.expectSubscription()
        subscription.request(10000)
        for (_ <- 0 until 10) {
          probe.expectNext(Success(1))
        }

        probe.expectComplete()
      }
    }

    "failing in backpressure mode" should {
      "stop pulling in elements" in {
        val probe = TestSubscriber.manualProbe[Try[Int]]()
        val sut = BidiFlow.fromGraph(new CircuitBreakerStage[Int, Int](defaultSettings))

        val flow = sut.join(alwaysFail)
        Source.repeat(1).take(10).via(flow).runWith(Sink.fromSubscriber(probe))

        val subscription = probe.expectSubscription()
        subscription.request(10000)
        for (_ <- 0 until 3) {
          probe.expectNext(expectedFailure)
        }

        probe.expectNoMessage(500.millis)
        subscription.cancel()
      }

      "pull a single element to serve as trial run" in {
        val probe = TestSubscriber.manualProbe[Try[Int]]()
        val sut = BidiFlow.fromGraph(new CircuitBreakerStage[Int, Int](defaultSettings))

        val flow = sut.join(alwaysFail)
        Source.repeat(1).via(flow).runWith(Sink.fromSubscriber(probe))

        val subscription = probe.expectSubscription()
        subscription.request(10000)
        for (_ <- 0 until 3) {
          probe.expectNext(expectedFailure)
        }

        probe.expectNoMessage(750.millis)
        probe.expectNext(expectedFailure)
        probe.expectNoMessage(750.millis)
        subscription.cancel()
      }

      "exponentially back off its attempts to close" in {
        val probe = TestSubscriber.manualProbe[Try[Int]]()
        val sut = BidiFlow.fromGraph(new CircuitBreakerStage[Int, Int](defaultSettings))

        val flow = sut.join(alwaysFail)
        Source.repeat(1).via(flow).runWith(Sink.fromSubscriber(probe))

        val subscription = probe.expectSubscription()
        subscription.request(10000)
        for (_ <- 0 until 3) {
          probe.expectNext(expectedFailure)
        }

        probe.expectNoMessage(750.millis)
        probe.expectNext(expectedFailure)
        probe.expectNoMessage(1750.millis)
        probe.expectNext(expectedFailure)
        subscription.cancel()
      }

      "close successfully if the probe succeeds" in {
        val probe = TestSubscriber.manualProbe[Try[Int]]()
        val sut = BidiFlow.fromGraph(new CircuitBreakerStage[Int, Int](defaultSettings))
        var count = 0
        val flow = sut join Flow.fromFunction { in: Int =>
          count += 1
          if (count > 3) Success(in)
          else expectedFailure
        }
        Source.repeat(1).via(flow).runWith(Sink.fromSubscriber(probe))

        val subscription = probe.expectSubscription()
        subscription.request(10000)
        for (_ <- 0 until 3) {
          probe.expectNext(expectedFailure)
        }

        probe.expectNoMessage(750.millis)

        for (_ <- 0 until 5000) {
          probe.expectNext(Success(1))
        }

        subscription.cancel()
      }
    }

    "failing in bypassmode" should {
      val bypassSettings = defaultSettings.copy(mode = CircuitBreakerMode.Bypass)
      "emit predictable exceptions once tripped" in {
        val probe = TestSubscriber.manualProbe[Try[Int]]()
        val sut = BidiFlow.fromGraph(new CircuitBreakerStage[Int, Int](bypassSettings))

        val flow = sut.join(alwaysFail)
        Source.repeat(1).via(flow).runWith(Sink.fromSubscriber(probe))

        val subscription = probe.expectSubscription()
        subscription.request(10000)
        for (_ <- 0 until 3) {
          probe.expectNext(expectedFailure)
        }

        for (_ <- 3 until 10000) {
          val next = probe.expectNext()
          next should matchPattern {
            case Failure(CircuitBreakerMode.CircuitBreakerIsOpen(1, _)) =>
          }
        }

        subscription.cancel()
      }

      "consume one input element for each output" in assertAllStagesStopped {
        val probe = TestSubscriber.manualProbe[Try[Int]]()
        val sut = BidiFlow.fromGraph(new CircuitBreakerStage[Int, Int](bypassSettings))

        val flow = sut.join(alwaysFail)
        Source.repeat(1).take(500).via(flow).runWith(Sink.fromSubscriber(probe))

        val subscription = probe.expectSubscription()
        subscription.request(10000)
        for (_ <- 0 until 500) {
          probe.expectNext()
        }

        probe.expectComplete()
      }

      "recover from the error successfully" in assertAllStagesStopped {
        val probe = TestSubscriber.manualProbe[Try[Int]]()
        val sut = BidiFlow.fromGraph(new CircuitBreakerStage[Int, Int](bypassSettings))
        var count = 0
        val flow = sut.join(Flow.fromFunction { a: Int =>
          count += 1
          if (count <= 3) expectedFailure
          else Success(a)
        })
        Source.repeat(1).via(flow).runWith(Sink.fromSubscriber(probe))

        val subscription = probe.expectSubscription()
        subscription.request(3)
        for (_ <- 0 until 3) {
          probe.expectNext(expectedFailure)
        }

        probe.expectNoMessage(1.5.second)
        subscription.request(10000)

        for (_ <- 0 until 10000) {
          probe.expectNext(Success(1))
        }

        subscription.cancel()
      }
    }
  }

  "The exponential backoff" must {
    def makeSut(max: Duration = 1.day) = {
      val reset = ResetSettings(1.second, max, 5)
      val settings = CircuitBreakerSettings(Tolerance.FailureFraction(1, 1.second), reset)
      new CircuitBreakerStage(settings).calculateNextDelay _
    }

    "increase by the configured factor" in {
      val sut = makeSut()
      val first = Random.nextInt(20) + 1
      sut(first.seconds) shouldEqual first.seconds * 5
    }

    "ignore infinite maximums" in {
      val sut = makeSut(Duration.Inf)
      val first = Random.nextInt(20) + 1
      sut(first.days) shouldEqual first.days * 5

    }

    "cap the growth when required" in {
      val sut = makeSut()
      val first = Random.nextInt(20) + 5

      sut(first.hours) shouldEqual 1.day
    }
  }
}
