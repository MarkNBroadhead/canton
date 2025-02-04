// Copyright (c) 2022 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.digitalasset.canton.logging

import java.util.concurrent.TimeUnit

import com.digitalasset.canton.util.ErrorUtil

import org.scalactic.source
import org.scalatest.Assertion
import org.scalatest.matchers.should.Matchers._
import org.slf4j.event.{Level, SubstituteLoggingEvent}
import org.slf4j.helpers
import org.slf4j.Logger

import scala.jdk.CollectionConverters._
import scala.collection.immutable.ListMap

/** Test logger that just writes the events into a queue for inspection
  */
@SuppressWarnings(Array("org.wartremover.warts.Null"))
class NamedEventCapturingLogger(
    val name: String,
    val properties: ListMap[String, String] = ListMap.empty,
    outputLogger: Option[Logger] = None,
) extends NamedLoggerFactory {

  val eventQueue: java.util.concurrent.BlockingQueue[SubstituteLoggingEvent] =
    new java.util.concurrent.LinkedBlockingQueue[SubstituteLoggingEvent]()

  private def pollEventQueue(ts: Option[Long]): SubstituteLoggingEvent = {
    val event = ts match {
      case None => eventQueue.poll()
      case Some(millis) => eventQueue.poll(millis, TimeUnit.MILLISECONDS)
    }
    if (event != null) {
      outputLogger.foreach(
        _.debug(s"Captured ${event.getLoggerName} ${event.getLevel} ${event.getMessage}")
      )
    }
    event
  }

  val eventSeq: Seq[SubstituteLoggingEvent] = eventQueue.asScala.toSeq

  private val logger = new helpers.SubstituteLogger(name, eventQueue, false)

  override def appendUnnamedKey(key: String, value: String): NamedLoggerFactory = this
  override def append(key: String, value: String): NamedLoggerFactory = this

  override private[logging] def getLogger(fullName: String) = logger

  def tryToPollMessage(
      expectedMessage: String,
      expectedLevel: Level,
      expectedThrowable: Throwable = null,
  ): Unit = {
    val event = eventQueue.peek()
    if (
      event != null && event.getMessage == expectedMessage && event.getLevel == expectedLevel && event.getThrowable == expectedThrowable
    ) {
      pollEventQueue(None)
    }
  }

  def assertNextMessageIs(
      expectedMessage: String,
      expectedLevel: Level,
      expectedThrowable: Throwable = null,
      timeoutMillis: Long = 2000,
  )(implicit pos: source.Position): Assertion =
    assertNextMessage(_ shouldBe expectedMessage, expectedLevel, expectedThrowable, timeoutMillis)

  def assertNextMessage(
      messageAssertion: String => Assertion,
      expectedLevel: Level,
      expectedThrowable: Throwable = null,
      timeoutMillis: Long = 2000,
  )(implicit pos: source.Position): Assertion =
    assertNextEvent(
      { event =>
        withClue("Unexpected log message: ") {
          messageAssertion(event.getMessage)
        }
        withClue("Unexpected log level: ") {
          event.getLevel shouldBe expectedLevel
        }
        withClue("Unexpected throwable: ") {
          event.getThrowable shouldBe expectedThrowable
        }
      },
      timeoutMillis,
    )

  def assertNextEvent(assertion: SubstituteLoggingEvent => Assertion, timeoutMillis: Long = 2000)(
      implicit pos: source.Position
  ): Assertion =
    Option(pollEventQueue(Some(timeoutMillis))) match {
      case None => fail("Missing log event.")
      case Some(event) => assertion(event)
    }

  def assertNoMoreEvents(timeoutMillis: Long = 0)(implicit pos: source.Position): Assertion =
    Option(eventQueue.poll(timeoutMillis, TimeUnit.MILLISECONDS)) match {
      case None => succeed
      case Some(event) =>
        val argumentsString = Option(event.getArgumentArray) match {
          case Some(args) => args.mkString("{", ", ", "}")
          case None => ""
        }

        val throwableString = Option(event.getThrowable) match {
          case Some(t) => "\n" + ErrorUtil.messageWithStacktrace(t)
          case None => ""
        }

        fail(
          s"Unexpected log event: ${event.getLevel} ${event.getLoggerName} - ${event.getMessage} $argumentsString$throwableString"
        )
    }
}
