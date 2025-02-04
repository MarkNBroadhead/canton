// Copyright (c) 2022 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.digitalasset.canton.participant.protocol

import cats.Monad
import cats.data.OptionT
import cats.syntax.either._
import cats.syntax.foldable._
import com.digitalasset.canton.BaseTest
import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.participant.RequestCounter
import com.digitalasset.canton.participant.protocol.RequestJournal.RequestState._
import com.digitalasset.canton.participant.protocol.RequestJournal.{
  NonterminalRequestState,
  RequestData,
  RequestState,
  RequestStateWithCursor,
}
import com.digitalasset.canton.participant.store.PreHookRequestJournalStore.CleanCounterHook
import com.digitalasset.canton.participant.store.memory.InMemoryRequestJournalStore
import com.digitalasset.canton.participant.store.{PreHookRequestJournalStore, RequestJournalStore}
import com.digitalasset.canton.store.CursorPrehead
import com.digitalasset.canton.tracing.TraceContext
import com.digitalasset.canton.util.MonadUtil
import org.scalatest.Assertion
import org.scalatest.wordspec.{AnyWordSpec, AsyncWordSpec}

import java.time.Instant
import java.util.ConcurrentModificationException
import scala.concurrent.Future

@SuppressWarnings(Array("org.wartremover.warts.IsInstanceOf"))
class RequestJournalTest extends AsyncWordSpec with BaseTest {

  def mk(
      initRc: RequestCounter,
      store: RequestJournalStore = new InMemoryRequestJournalStore(loggerFactory),
  ): RequestJournal =
    new RequestJournal(store, loggerFactory, initRc)

  def insertWithCursor(
      rj: RequestJournal,
      rc: RequestCounter,
      state: RequestStateWithCursor,
      requestTimestamp: CantonTimestamp,
      commitTime: Option[CantonTimestamp] = None,
  )(implicit traceContext: TraceContext): Future[Future[Unit]] =
    for {
      _ <- rj.insert(rc, requestTimestamp)
      cursorF <- transitTo(rj, rc, requestTimestamp, state, commitTime).getOrElse(
        fail(s"Request $rc: Cursor future for request state $state")
      )
    } yield cursorF

  def transitTo(
      rj: RequestJournal,
      rc: RequestCounter,
      requestTimestamp: CantonTimestamp,
      target: RequestState,
      commitTimeO: Option[CantonTimestamp] = None,
  ): OptionT[Future, Future[Unit]] =
    OptionT[Future, Future[Unit]] {

      def step(
          future: Option[Future[Unit]]
      ): Future[Either[Option[Future[Unit]], Option[Future[Unit]]]] =
        for {
          current <- rj
            .query(rc)
            .getOrElse(throw new IllegalArgumentException(s"request counter $rc not found"))
            .map(_.state)
          result <-
            if (current < target) {
              current.next.value match {
                case Clean =>
                  val commitTime =
                    commitTimeO.getOrElse(fail(s"No commit time for request $rc given"))
                  rj.terminate(rc, requestTimestamp, current, commitTime)
                    .map(fut => Right(Some(fut)))
                case next: NonterminalRequestState =>
                  rj.transit(rc, requestTimestamp, current, next).map(Either.left)
              }
            } else Future.successful(Right(future))
        } yield result

      Monad[Future].tailRecM(Option.empty[Future[Unit]])(step)
    }

  def mkWith(
      initRc: RequestCounter,
      inserts: List[(RequestCounter, CantonTimestamp)],
      store: RequestJournalStore = new InMemoryRequestJournalStore(loggerFactory),
  ): Future[RequestJournal] = {
    val rj = mk(initRc, store)

    inserts
      .traverse_ { case (rc, timestamp) =>
        rj.insert(rc, timestamp)
      }
      .map(_ => rj)
  }

  def assertPresent(rj: RequestJournal, presentRcs: List[(RequestCounter, CantonTimestamp)])(
      expectedState: (RequestCounter, CantonTimestamp) => RequestData
  ): Future[Assertion] = {
    presentRcs
      .traverse_ { case (rc, ts) =>
        rj.query(rc)
          .value
          .map(result =>
            assert(
              result.contains(expectedState(rc, ts)),
              s"Request counter $rc has state $result, but should be ${expectedState(rc, ts)}",
            )
          )
      }
      .map((_: Unit) => succeed)
  }

  def assertAbsent(rj: RequestJournal, absentRcs: List[RequestCounter]): Future[Assertion] = {
    absentRcs
      .traverse_ { rc =>
        rj.query(rc)
          .value
          .map(result => assert(result.isEmpty, s"Found state $result for request counter $rc"))
      }
      .map((_: Unit) => succeed)
  }

  val commitTime: CantonTimestamp =
    CantonTimestamp.assertFromInstant(Instant.parse("2020-01-03T00:00:00.00Z"))

  "created with initial request counter 0" when {
    val initRc = 0L
    val rj = mk(initRc)

    "queries" should {
      s"return $None" in {
        assertAbsent(rj, List(initRc, initRc + 5, initRc - 1)).map { _ =>
          rj.numberOfDirtyRequests shouldBe 0
        }
      }
    }

    val inserts = Map(
      initRc -> CantonTimestamp.assertFromInstant(Instant.parse("2019-01-03T10:15:30.00Z")),
      initRc + 1 -> CantonTimestamp.assertFromInstant(Instant.parse("2019-01-03T10:15:31.00Z")),
      initRc + 4 -> CantonTimestamp.assertFromInstant(Instant.parse("2019-01-03T10:16:11.00Z")),
    )
    val nonInserts = List(initRc - 1, initRc + 2, initRc + 3, initRc + 5, RequestCounter.MaxValue)

    "after inserting request counters" should {

      "queries should return the right results" in {
        for {
          rj <- mkWith(initRc, inserts.toList)
          _ <- assertPresent(rj, inserts.toList)((rc, timestamp) =>
            RequestData(rc, Pending, timestamp)
          )
          _ <- assertAbsent(rj, nonInserts)
        } yield {
          rj.numberOfDirtyRequests shouldBe 3
        }
      }

      "the cursors should be at the correct positions" in {
        val store = new InMemoryRequestJournalStore(loggerFactory)
        for {
          rj <- mkWith(initRc, inserts.toList, store)
          _ <- rj.flush()
          prehead <- store.preheadClean
        } yield {
          prehead shouldBe None
          rj.numberOfDirtyRequests shouldBe 3
        }
      }
    }

    s"transiting the first request to $Clean" should {
      s"advance the $Clean cursor" in {
        val store = new InMemoryRequestJournalStore(loggerFactory)
        for {
          rj <- mkWith(initRc, inserts.toList, store)
          cf1 <- transitTo(rj, initRc, inserts(initRc), Clean, Some(commitTime)).value
          _ = assert(cf1.isDefined, "return a Future")
          _ <- rj.flush()
          prehead <- store.preheadClean
          _ <- cf1.value
        } yield {
          prehead shouldBe Some(CursorPrehead(initRc, inserts(initRc)))
          rj.numberOfDirtyRequests shouldBe 2
        }
      }
    }

    s"transiting the first two requests to $Clean and inserting the next request as $Clean" should {
      s"advance the $Clean cursor" in {
        val store = new InMemoryRequestJournalStore(loggerFactory)
        for {
          rj <- mkWith(initRc, inserts.toList, store)
          cf1 <- transitTo(rj, initRc, inserts(initRc), Clean, Some(commitTime)).value
          cf2 <- transitTo(rj, initRc + 1, inserts(initRc + 1), Clean, Some(commitTime)).value
          ts = CantonTimestamp.assertFromInstant(Instant.parse("2019-01-03T10:16:10.00Z"))
          cf3 <- insertWithCursor(rj, initRc + 2, Clean, ts, Some(commitTime))
          _ <- rj.flush()
          prehead <- store.preheadClean
          _ <- List(cf1.value, cf2.value, cf3).sequence_
        } yield {
          prehead shouldBe Some(CursorPrehead(initRc + 2L, ts))
          rj.numberOfDirtyRequests shouldBe 1
        }
      }
    }

    s"transiting only the second request to $Confirmed" should {
      val transitRc = initRc + 1
      def setup(): Future[RequestJournal] =
        for {
          rj <- mkWith(initRc, inserts.toList)
          pf2 <- rj.transit(transitRc, inserts(transitRc), Pending, Confirmed)
        } yield {
          pf2 shouldBe None
          rj
        }

      "query returns the updated result and leave the others unchanged" in {
        for {
          rj <- setup()
          _ <- assertPresent(rj, inserts.toList)((rc, timestamp) =>
            RequestData(rc, if (rc == transitRc) Confirmed else Pending, timestamp)
          )
          _ <- assertAbsent(rj, nonInserts)
        } yield rj.numberOfDirtyRequests shouldBe 3
      }
    }

    "inserting the requests again" should {
      "fail" in {
        for {
          rj <- mkWith(initRc, inserts.toList)
          _ <- MonadUtil.sequentialTraverse_(inserts) { case (rc, timestamp) =>
            loggerFactory.assertInternalErrorAsync[IllegalArgumentException](
              rj.insert(rc, timestamp),
              _ => succeed, // Error messages are very different.
            )
          }
        } yield succeed
      }
    }

    "skipping transit states" should {
      "fail" in {
        for {
          rj <- mkWith(initRc, inserts.toList)
          _ <- loggerFactory.assertInternalErrorAsync[ConcurrentModificationException](
            rj.terminate(initRc, inserts(initRc), Confirmed, commitTime),
            _.getMessage shouldBe "Request 0: Concurrent request journal modification.\nPrevious state: Pending\nExpected state: Confirmed",
          )
          confirmed <- rj.transit(initRc, inserts(initRc), Pending, Confirmed)
          _ = loggerFactory.assertInternalError[IllegalArgumentException](
            rj.transit(initRc, inserts(initRc), Pending, Pending),
            _.getMessage shouldBe "Request 0: State Pending is not a predecessor for Pending",
          )
        } yield {
          assert(confirmed.isEmpty)
        }
      }
    }

    "wrong state transitions" should {
      "fail" in {
        for {
          rj <- mkWith(initRc, inserts.toList)
          _ <- rj.transit(initRc, inserts(initRc), Pending, Confirmed)
        } yield {
          loggerFactory.assertInternalError[IllegalArgumentException](
            rj.transit(initRc + 1, inserts(initRc), Pending, Pending),
            _.getMessage shouldBe "Request 1: State Pending is not a predecessor for Pending",
          )
          loggerFactory.assertInternalError[IllegalArgumentException](
            rj.terminate(initRc, inserts(initRc), Pending, commitTime),
            _.getMessage shouldBe "Request 0: State Pending is not a predecessor for Clean",
          )
          loggerFactory.assertInternalError[IllegalArgumentException](
            rj.terminate(initRc, inserts(initRc), Clean, commitTime),
            _.getMessage shouldBe "Request 0: State Clean is not a predecessor for Clean",
          )
        }
      }
    }

    "modifying a nonexisting request" should {
      "fail" in {
        val rj = mk(initRc)
        for {
          _ <- loggerFactory.assertInternalErrorAsync[IllegalArgumentException](
            rj.transit(initRc, CantonTimestamp.Epoch, Pending, Confirmed),
            _.getMessage shouldBe "Cannot transit non-existing request with request counter 0",
          )
          _ <- loggerFactory.assertInternalErrorAsync[IllegalArgumentException](
            rj.terminate(initRc, CantonTimestamp.Epoch, Confirmed, commitTime),
            _.getMessage shouldBe "Cannot transit non-existing request with request counter 0",
          )
        } yield succeed
      }
    }

    "providing the wrong request timestamp" should {
      "fail" in {
        val rj = mk(initRc)
        for {
          _ <- rj.insert(initRc, CantonTimestamp.Epoch)
          _failure <- loggerFactory.assertInternalErrorAsync[IllegalStateException](
            rj.transit(initRc, CantonTimestamp.ofEpochSecond(1), Pending, Confirmed),
            _.getMessage shouldBe s"Request 0: Inconsistent timestamps for request.\nStored: ${CantonTimestamp.Epoch}\nExpected: ${CantonTimestamp
              .ofEpochSecond(1)}",
          )
        } yield succeed
      }
    }

    "terminating with a too early commit time" should {
      "fail" in {
        for {
          rj <- mkWith(initRc, List(initRc -> CantonTimestamp.Epoch))
          _ <- transitTo(rj, initRc, CantonTimestamp.Epoch, Confirmed).value
          _ <- loggerFactory.assertInternalErrorAsync[IllegalArgumentException](
            rj.terminate(
              initRc,
              CantonTimestamp.Epoch,
              Confirmed,
              CantonTimestamp.ofEpochMilli(-1),
            ),
            _.getMessage shouldBe "Request 0: Commit time 1969-12-31T23:59:59.999Z must be at least the request timestamp 1970-01-01T00:00:00Z",
          )
        } yield succeed
      }
    }

    "cursor futures are consistent with the persisted cursors" in {
      val rjs = new InMemoryRequestJournalStore(loggerFactory)
      val hooked = new PreHookRequestJournalStore(rjs, loggerFactory)
      val rj = new RequestJournal(hooked, loggerFactory, initRc)

      def cleanCounterHook3(clean4: Future[Unit]): CleanCounterHook = { prehead =>
        val CursorPrehead(rc, _requestTimestamp) = prehead
        if (rc < initRc + 4L) {
          hooked.setCleanCounterHook(cleanCounterHook3(clean4))
          Future.unit
        } else {
          assert(
            !clean4.isCompleted,
            s"Clean future for ${initRc + 4} must not complete before writing the clean counter $rc.",
          )
          for {
            state4 <- valueOrFail(rj.query(rc))(s"Request $rc's state")
            store4 <- valueOrFail(rjs.query(rc))(s"Request $rc's persisted state")
          } yield {
            assert(state4.state == Clean, s"Request $rc must be $Clean")
            assert(store4.state == Clean, s"Request $rc must be persisted as $Clean")
          }
        }
      }

      hooked.setCleanCounterHook { prehead =>
        val CursorPrehead(rc, _requestTimestamp) = prehead
        assert(rc == initRc, s"Clean counter set to $rc instead of ${initRc}")
        for {
          state0 <- valueOrFail(rj.query(rc))(s"request state for ${rc}")
          store0 <- valueOrFail(rjs.query(rc))(s"persisted request state for ${rc}")
        } yield {
          assert(state0.state == Clean, s"Request $rc must reach $Clean first.")
          assert(
            store0.state == Clean,
            s"Persisted request state must be $Clean before updating the Cur",
          )
        }
      }
      for {
        _ <- insertWithCursor(rj, initRc, Clean, CantonTimestamp.Epoch, Some(commitTime))
        ts2 = CantonTimestamp.ofEpochSecond(3)
        clean2 <- insertWithCursor(rj, initRc + 2, Clean, ts2, Some(commitTime))
        _ <- rj.flush()
        _ = hooked.setCleanCounterHook { _ =>
          assert(
            !clean2.isCompleted,
            s"Clean cursor promise must complete only after persisting the prehead",
          )
          valueOrFail(rjs.query(initRc + 1))(s"Request ${initRc + 1}'s persisted state").map {
            result =>
              assert(result.state == Clean, s"Clean state must be persisted before the cursor")
          }
        }
        _ <- insertWithCursor(
          rj,
          initRc + 1,
          Clean,
          CantonTimestamp.ofEpochSecond(1),
          Some(commitTime),
        )

        ts4 = CantonTimestamp.ofEpochSecond(8)
        pending4 <- rj.insert(initRc + 4, ts4)
        _ <- rj.flush()
        _ = hooked.setInsertHook { _ =>
          assert(
            !pending4.isCompleted,
            s"Pending cursor promise must complete only after persisting the states.",
          )
          Future.unit
        }
        ts3 = CantonTimestamp.ofEpochSecond(6)
        _ <- rj.insert(initRc + 3, ts3)

        clean4 <- valueOrFail(transitTo(rj, initRc + 4, ts4, Clean, Some(commitTime)))(
          s"transit ${initRc + 4} to $Clean"
        )
        _ <- rj.flush()
        _ = hooked.setReplaceHook { (_, _, _, _, _) =>
          assert(
            !clean4.isCompleted,
            s"Clean cursor future for ${initRc + 4} must not be completed before persisting request ${initRc + 3}",
          )
          Future.unit
        }
        _ = hooked.setCleanCounterHook(cleanCounterHook3(clean4))
        _ <- valueOrFail(transitTo(rj, initRc + 3, ts3, Clean, Some(commitTime)))(
          s"transit ${initRc + 3} to $Clean"
        )
        _ <- rj.flush()
      } yield succeed
    }

    "preheads do not move backwards" in {
      val rjs = new InMemoryRequestJournalStore(loggerFactory)
      val hooked = new PreHookRequestJournalStore(rjs, loggerFactory)
      val rj = new RequestJournal(hooked, loggerFactory, initRc)
      val ts1 = CantonTimestamp.ofEpochSecond(1)

      // No test for the Pending prehead because we don't have a hook into that.

      hooked.setCleanCounterHook { _rc0 =>
        // this runs while the clean cursor for initRc is written to the store
        for {
          cursor1 <- insertWithCursor(rj, initRc + 1, Clean, ts1, Some(commitTime))
          _ <- cursor1
        } yield ()
      }
      for {
        cursor0 <- insertWithCursor(rj, initRc, Clean, CantonTimestamp.Epoch, Some(commitTime))
        _ <- cursor0
        prehead <- rjs.preheadClean
      } yield {
        prehead shouldBe Some(CursorPrehead(initRc + 1, ts1))
      }
    }
  }

  "created with a non-zero start value" when {
    val initRc = 0x100000000L

    val insertsBelowCursor =
      List(
        (initRc - 1, CantonTimestamp.assertFromInstant(Instant.parse("2011-12-11T00:00:00.00Z"))),
        (Long.MinValue, CantonTimestamp.ofEpochSecond(0)),
      )

    "inserting lower request counters" should {
      "fail" in {
        val rj = mk(initRc)
        MonadUtil
          .sequentialTraverse_(insertsBelowCursor) { case (rc, timestamp) =>
            loggerFactory.assertInternalErrorAsync[IllegalArgumentException](
              rj.insert(rc, timestamp),
              _.getMessage should fullyMatch regex "Request counter .* is below Pending's front value .*",
            )
          }
          .map(_ => succeed)
      }
    }

    "inserting several request counters and progressing them somewhat" should {
      val initTs = CantonTimestamp.ofEpochSecond(1)
      val inserts = List(
        ((initRc + 4, CantonTimestamp.ofEpochSecond(5)), Confirmed, None),
        ((initRc + 2, CantonTimestamp.ofEpochSecond(3)), Clean, Some(commitTime)),
        ((initRc, initTs), Pending, None),
      )

      def setup(): Future[RequestJournal] =
        for {
          rj <- mkWith(initRc, inserts.map(_._1))
          _ <- inserts.traverse_ { case ((rc, timestamp), target, commitTime) =>
            transitTo(rj, rc, timestamp, target, commitTime).value
          }
        } yield rj

      "queries are correct" in {
        for {
          rj <- setup()
          _ <- inserts.traverse_ { case ((rc, timestamp), target, commitTime) =>
            rj.query(rc)
              .value
              .map(result =>
                assert(result.contains(new RequestData(rc, target, timestamp, commitTime, None)))
              )
          }
        } yield rj.numberOfDirtyRequests shouldBe 2
      }

      val insertedRc = initRc + 1
      val ts = CantonTimestamp.ofEpochSecond(2)
      s"inserting an intermediate request counter at $Clean progresses only the $Pending cursor" in {
        for {
          rj <- setup()
          _ <- rj.insert(insertedRc, ts)
          cf <- transitTo(rj, insertedRc, ts, Clean, Some(commitTime)).value
          _ <- rj.flush()
        } yield {
          assert(!cf.value.isCompleted)
          rj.numberOfDirtyRequests shouldBe 2
        }
      }

      s"additionally progressing the lowest request counter also advances the $Clean cursor" in {
        for {
          rj <- setup()
          _ <- rj.insert(insertedRc, ts)
          cf1 <- transitTo(rj, insertedRc, ts, Clean, Some(commitTime)).value
          cf0 <- transitTo(rj, initRc, initTs, Clean, Some(commitTime)).value
        } yield {
          assert(cf1.value.isCompleted)
          assert(cf0.value.isCompleted)
          rj.numberOfDirtyRequests shouldBe 1
        }
      }

      "correctly count the requests" in {
        for {
          rj <- setup()
          _ <- rj.insert(insertedRc, ts)
          _ <- rj.flush()
          totalCount <- rj.size()
          countUntilTs <- rj.size(end = Some(ts))
          countFromTs <- rj.size(start = ts)
          count45 <- rj.size(
            CantonTimestamp.ofEpochSecond(4),
            Some(CantonTimestamp.ofEpochSecond(5)),
          )
          count56 <- rj.size(
            CantonTimestamp.ofEpochSecond(5),
            Some(CantonTimestamp.ofEpochSecond(6)),
          )
        } yield {
          totalCount shouldBe 4
          countUntilTs shouldBe 2
          countFromTs shouldBe 3
          count45 shouldBe 1
          count56 shouldBe 1
          rj.numberOfDirtyRequests shouldBe 3
        }
      }
    }
  }

  s"when created with head ${RequestCounter.MaxValue}" should {
    val initRc = RequestCounter.MaxValue
    val rj = mk(initRc)

    s"adding this request fails" in {
      for {
        _ <- loggerFactory.assertInternalErrorAsync[IllegalArgumentException](
          rj.insert(initRc, CantonTimestamp.Epoch),
          _.getMessage shouldBe "The request counter 9223372036854775807 cannot be used.",
        )
        _ <- rj.flush()
      } yield succeed
    }
  }
}
@SuppressWarnings(Array("org.wartremover.warts.IsInstanceOf"))
class RequestStateTest extends AnyWordSpec with BaseTest {

  "All request states" should {
    val testCases = Table[Option[RequestState], RequestState, Option[RequestState], Boolean](
      ("predecessor", "state", "successor", "has cursor"),
      (None, Pending, Some(Confirmed), true),
      (Some(Pending), Confirmed, Some(Clean), false),
      (Some(Confirmed), Clean, None, true),
    )

    "correctly name their predecessors and successors" in {
      forEvery(testCases) { (pred, state, succ, _) =>
        assert(pred.forall(_ < state))
        assert(succ.forall(state < _))
        assert(state.compare(state) == 0)
        assert(pred.forall(state.isSuccessorOf))
        assert(state.next == succ)
      }
    }

    "correctly specify whether they have a cursor" in {
      forEvery(testCases) { (_, state, _, hasCursor) =>
        assert(state.hasCursor == hasCursor)
        assert(state.isInstanceOf[RequestStateWithCursor] == hasCursor)
      }
    }

    "have a unique index" in {
      val indices = RequestState.states.toVector.map(_.index)
      assert(indices.distinct == indices)
    }
  }
}

object RequestStateTest {
  val statesWithCursor: Array[RequestStateWithCursor] = Array(Pending, Clean)
}
