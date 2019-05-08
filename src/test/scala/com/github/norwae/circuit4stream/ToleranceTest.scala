package com.github.norwae.circuit4stream

import java.time.Instant

import com.github.norwae.circuit4stream.Tolerance.FailureFrequency
import org.scalatest.{LoneElement, Matchers, WordSpec}

import scala.concurrent.duration._
import scala.util.{Failure, Success}

class ToleranceTest extends WordSpec with Matchers with LoneElement {
  "The FailureFraction tolerance mode" must {
    val sut = Tolerance.FailureFraction(0.5, 1.second)

    "start with a clean history" in {
      sut.initialLog shouldBe empty
    }

    "clean expired events" in {
      val expiredLog = Vector(
        new sut.Event(Instant.now().minusSeconds(5), true),
        new sut.Event(Instant.now().minusSeconds(4), true),
        new sut.Event(Instant.now().minusSeconds(3), true),
        new sut.Event(Instant.now().minusSeconds(5), true)
      )
      val (newLog, open) = sut(expiredLog, Success(18))
      val element = newLog.loneElement
      val millis = java.time.Duration.between(element.time, Instant.now).toMillis

      element.failed shouldBe false
      millis should be <= 100L
      open shouldBe false
    }

    "tolerate up to the threshold failures" in {
      val expiredLog = Vector(
        new sut.Event(Instant.now().minusMillis(5), true),
        new sut.Event(Instant.now().minusMillis(4), true),
        new sut.Event(Instant.now().minusMillis(3), true),
        new sut.Event(Instant.now().minusMillis(5), false),
        new sut.Event(Instant.now().minusMillis(5), false)
      )
      val (_, open) = sut(expiredLog, Success(18))
      open shouldBe false
    }

    "trigger once the threshold is exceeed" in {
      val expiredLog = Vector(
        new sut.Event(Instant.now().minusMillis(5), true),
        new sut.Event(Instant.now().minusMillis(4), true),
        new sut.Event(Instant.now().minusMillis(3), true),
        new sut.Event(Instant.now().minusMillis(5), false),
        new sut.Event(Instant.now().minusMillis(5), false),
        new sut.Event(Instant.now().minusMillis(5), false)
      )
      val (_, open) = sut(expiredLog, Failure(new InterruptedException))
      open shouldBe true
    }
  }

  "The FailureFrequency tolerance mode" must {
    val sut = FailureFrequency(5, 1.second)

    "start with a clean history" in {
      sut.initialLog shouldBe empty
    }

    "clean expired events" in {
      val expiredLog = Vector(
        Instant.now().minusSeconds(5),
        Instant.now().minusSeconds(4),
        Instant.now().minusSeconds(3),
        Instant.now().minusSeconds(5)
      )
      val (newLog, open) = sut(expiredLog, Success(18))

      newLog shouldBe empty
      open shouldBe false
    }

    "tolerate up to the threshold failures" in {
      val expiredLog = Vector(
        Instant.now().minusMillis(5),
        Instant.now().minusMillis(4),
        Instant.now().minusMillis(3),
        Instant.now().minusMillis(5),
      )
      val (_, open) = sut(expiredLog, Success(18))
      open shouldBe false
    }

    "trigger once the threshold is exceeed" in {
      val expiredLog = Vector(
        Instant.now().minusMillis(5),
        Instant.now().minusMillis(4),
        Instant.now().minusMillis(5),
        Instant.now().minusMillis(5)
      )
      val (_, open) = sut(expiredLog, Failure(new InterruptedException))
      open shouldBe true
    }
  }
}
