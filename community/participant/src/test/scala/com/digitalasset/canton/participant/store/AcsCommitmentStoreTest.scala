// Copyright (c) 2022 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.digitalasset.canton.participant.store

import com.digitalasset.canton.crypto.provider.symbolic.{SymbolicCrypto, SymbolicPureCrypto}
import com.digitalasset.canton.crypto.{CryptoPureApi, Fingerprint, HashPurpose, LtHash16}
import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.topology.{DomainId, ParticipantId, UniqueIdentifier}
import com.digitalasset.canton.participant.event.RecordTime
import com.digitalasset.canton.protocol.messages.{
  AcsCommitment,
  CommitmentPeriod,
  SignedProtocolMessage,
}
import com.digitalasset.canton.protocol.ContractMetadata
import com.digitalasset.canton.store.PrunableByTimeTest
import com.digitalasset.canton.time.PositiveSeconds
import com.digitalasset.canton.util.FutureUtil
import com.digitalasset.canton.version.ProtocolVersion
import com.digitalasset.canton.{BaseTest, LfPartyId}
import com.google.protobuf.ByteString
import org.scalatest.wordspec.AsyncWordSpec

import scala.collection.immutable.SortedSet
import scala.concurrent.ExecutionContext
import scala.concurrent.duration._

trait CommitmentStoreBaseTest extends AsyncWordSpec with BaseTest {
  val domainId = DomainId(UniqueIdentifier.tryFromProtoPrimitive("domain::domain"))
  val cryptoApi: CryptoPureApi = new SymbolicPureCrypto

  val symbolicVault =
    SymbolicCrypto
      .tryCreate(Seq(Fingerprint.tryCreate("test")), Seq(), timeouts, loggerFactory)
      .privateCrypto

  val localId = ParticipantId(UniqueIdentifier.tryFromProtoPrimitive("localParticipant::domain"))
  val remoteId = ParticipantId(UniqueIdentifier.tryFromProtoPrimitive("remoteParticipant::domain"))
  val remoteId2 = ParticipantId(
    UniqueIdentifier.tryFromProtoPrimitive("remoteParticipant2::domain")
  )
  val interval = PositiveSeconds.ofSeconds(1)

  def ts(time: Int): CantonTimestamp = CantonTimestamp.ofEpochSecond(time.toLong)
  def meta(stakeholders: LfPartyId*): ContractMetadata =
    ContractMetadata.tryCreate(Set.empty, stakeholders.toSet, maybeKeyWithMaintainers = None)
  def period(fromExclusive: Int, toInclusive: Int) =
    CommitmentPeriod(ts(fromExclusive), ts(toInclusive), interval).value

  val dummyCommitment: AcsCommitment.CommitmentType = {
    val h = LtHash16()
    h.add("blah".getBytes())
    h.getByteString()
  }
  val dummyCommitment2: AcsCommitment.CommitmentType = {
    val h = LtHash16()
    h.add("yah mon".getBytes())
    h.getByteString()
  }

  lazy val dummySignature = FutureUtil
    .noisyAwaitResult(
      symbolicVault
        .sign(
          cryptoApi.digest(HashPurpose.AcsCommitment, dummyCommitment),
          Fingerprint.tryCreate("test"),
        )
        .value,
      "dummy signature",
      10.seconds,
    )
    .valueOrFail("failed to create dummy signature")

  val dummyCommitmentMsg =
    AcsCommitment.create(
      domainId,
      remoteId,
      localId,
      period(0, 1),
      dummyCommitment,
      defaultProtocolVersion,
    )
  val dummySigned = SignedProtocolMessage(dummyCommitmentMsg, dummySignature)

  val alice: LfPartyId = LfPartyId.assertFromString("Alice")
  val bob: LfPartyId = LfPartyId.assertFromString("bob")
  val charlie: LfPartyId = LfPartyId.assertFromString("charlie")
}

trait AcsCommitmentStoreTest extends CommitmentStoreBaseTest with PrunableByTimeTest {

  def acsCommitmentStore(mkWith: ExecutionContext => AcsCommitmentStore): Unit = {

    behave like prunableByTime(mkWith)

    def mk() = mkWith(executionContext)

    "successfully get a stored computed commitment" in {
      val store = mk()

      for {
        _ <- store.storeComputed(period(0, 1), remoteId, dummyCommitment)
        _ <- store.storeComputed(period(1, 2), remoteId, dummyCommitment)
        found1 <- store.getComputed(period(0, 1), remoteId)
        found2 <- store.getComputed(period(0, 2), remoteId)
        found3 <- store.getComputed(period(0, 1), remoteId2)
      } yield {
        found1.toList shouldBe List(period(0, 1) -> dummyCommitment)
        found2.toList shouldBe List(
          period(0, 1) -> dummyCommitment,
          period(1, 2) -> dummyCommitment,
        )
        found3.toList shouldBe empty
      }
    }

    "correctly compute outstanding commitments" in {
      val store = mk()

      for {
        outstanding0 <- store.outstanding(ts(0), ts(10), None)
        _ <- store.markOutstanding(period(1, 5), Set(remoteId, remoteId2))
        outstanding1 <- store.outstanding(ts(0), ts(10), None)
        _ <- store.markSafe(remoteId, period(1, 2))
        outstanding2 <- store.outstanding(ts(0), ts(10), None)
        _ <- store.markSafe(remoteId2, period(2, 3))
        outstanding3 <- store.outstanding(ts(0), ts(10), None)
        _ <- store.markSafe(remoteId, period(4, 6))
        outstanding4 <- store.outstanding(ts(0), ts(10), None)
        _ <- store.markSafe(remoteId2, period(1, 5))
        outstanding5 <- store.outstanding(ts(0), ts(10), None)
        _ <- store.markSafe(remoteId, period(2, 4))
        outstanding6 <- store.outstanding(ts(0), ts(10), None)
      } yield {
        outstanding0.toSet shouldBe Set.empty
        outstanding1.toSet shouldBe Set(period(1, 5) -> remoteId, period(1, 5) -> remoteId2)
        outstanding2.toSet shouldBe Set(
          period(2, 5) -> remoteId,
          period(1, 5) -> remoteId2,
        )
        outstanding3.toSet shouldBe Set(
          period(2, 5) -> remoteId,
          period(1, 2) -> remoteId2,
          period(3, 5) -> remoteId2,
        )
        outstanding4.toSet shouldBe Set(
          period(2, 4) -> remoteId,
          period(1, 2) -> remoteId2,
          period(3, 5) -> remoteId2,
        )
        outstanding5.toSet shouldBe Set(
          period(2, 4) -> remoteId
        )
        outstanding6.toSet shouldBe Set.empty
      }
    }

    "correctly compute the no outstanding commitment limit" in {
      val store = mk()

      val endOfTime = ts(10)
      for {
        limit0 <- store.noOutstandingCommitments(endOfTime)
        _ <- store.markOutstanding(period(0, 2), Set())
        _ <- store.markComputedAndSent(period(0, 2))
        limit1 <- store.noOutstandingCommitments(endOfTime)
        _ <- store.markOutstanding(period(2, 4), Set(remoteId, remoteId2))
        _ <- store.markComputedAndSent(period(2, 4))
        limit2 <- store.noOutstandingCommitments(endOfTime)
        _ <- store.markSafe(remoteId, period(2, 3))
        limit3 <- store.noOutstandingCommitments(endOfTime)
        _ <- store.markSafe(remoteId2, period(3, 4))
        limit4 <- store.noOutstandingCommitments(endOfTime)
        _ <- store.markSafe(remoteId2, period(2, 3))
        limit5 <- store.noOutstandingCommitments(endOfTime)
        _ <- store.markSafe(remoteId, period(3, 4))
        limit6 <- store.noOutstandingCommitments(endOfTime)
      } yield {
        limit0 shouldBe None
        limit1 shouldBe Some(ts(2))
        limit2 shouldBe Some(ts(2))
        limit3 shouldBe Some(ts(2))
        limit4 shouldBe Some(ts(2))
        limit5 shouldBe Some(ts(3))
        limit6 shouldBe Some(ts(4))
      }
    }

    "correctly compute the no outstanding commitment limit with gaps in commitments" in {
      val store = mk()

      val endOfTime = ts(10)
      for {
        limit0 <- store.noOutstandingCommitments(endOfTime)
        _ <- store.markOutstanding(period(0, 2), Set())
        _ <- store.markComputedAndSent(period(0, 2))
        limit1 <- store.noOutstandingCommitments(endOfTime)
        limit11 <- store.noOutstandingCommitments(ts(1))
        _ <- store.markOutstanding(period(2, 4), Set(remoteId, remoteId2))
        _ <- store.markComputedAndSent(period(2, 4))
        limit2 <- store.noOutstandingCommitments(endOfTime)
        limit21 <- store.noOutstandingCommitments(ts(2))
        limit22 <- store.noOutstandingCommitments(ts(3))
        limit23 <- store.noOutstandingCommitments(ts(4))
        _ <- store.markSafe(remoteId, period(2, 3))
        limit3 <- store.noOutstandingCommitments(endOfTime)
        limit31 <- store.noOutstandingCommitments(ts(2))
        limit32 <- store.noOutstandingCommitments(ts(3))
        limit33 <- store.noOutstandingCommitments(ts(4))
        _ <- store.markSafe(remoteId2, period(3, 4))
        limit4 <- store.noOutstandingCommitments(endOfTime)
        limit41 <- store.noOutstandingCommitments(ts(2))
        limit42 <- store.noOutstandingCommitments(ts(3))
        limit43 <- store.noOutstandingCommitments(ts(4))
        _ <- store.markSafe(remoteId, period(3, 4))
        limit5 <- store.noOutstandingCommitments(endOfTime)
        limit51 <- store.noOutstandingCommitments(ts(2))
        limit52 <- store.noOutstandingCommitments(ts(3))
        limit53 <- store.noOutstandingCommitments(ts(4))
        _ <- store.markSafe(remoteId2, period(2, 3))
        limit6 <- store.noOutstandingCommitments(endOfTime)
        limit61 <- store.noOutstandingCommitments(ts(3))
        _ <- store.markOutstanding(period(4, 6), Set(remoteId, remoteId2))
        _ <- store.markComputedAndSent(period(4, 6))
        limit7 <- store.noOutstandingCommitments(endOfTime)
        _ <- store.markOutstanding(period(6, 10), Set())
        _ <- store.markComputedAndSent(period(6, 10))
        limit8 <- store.noOutstandingCommitments(endOfTime)
        limit81 <- store.noOutstandingCommitments(ts(6))
      } yield {
        limit0 shouldBe None
        limit1 shouldBe Some(ts(2))
        limit11 shouldBe Some(ts(1))
        limit2 shouldBe Some(ts(2))
        limit21 shouldBe Some(ts(2))
        limit22 shouldBe Some(ts(2))
        limit23 shouldBe Some(ts(2))
        limit3 shouldBe Some(ts(2))
        limit31 shouldBe Some(ts(2))
        limit32 shouldBe Some(ts(2))
        limit33 shouldBe Some(ts(2))
        limit4 shouldBe Some(ts(2))
        limit41 shouldBe Some(ts(2))
        limit42 shouldBe Some(ts(2))
        limit43 shouldBe Some(ts(2))
        limit5 shouldBe Some(ts(4))
        limit51 shouldBe Some(ts(2))
        limit52 shouldBe Some(ts(2))
        limit53 shouldBe Some(ts(4))
        limit6 shouldBe Some(ts(4))
        limit61 shouldBe Some(ts(3))
        limit7 shouldBe Some(ts(4))
        limit8 shouldBe Some(ts(10))
        limit81 shouldBe Some(ts(4))
      }
    }

    "correctly search stored computed commitments" in {
      val store = mk()

      for {
        _ <- store.storeComputed(period(0, 1), remoteId, dummyCommitment)
        _ <- store.storeComputed(period(1, 2), remoteId2, dummyCommitment)
        _ <- store.storeComputed(period(1, 2), remoteId, dummyCommitment)
        _ <- store.storeComputed(period(2, 3), remoteId, dummyCommitment)
        found1 <- store.searchComputedBetween(ts(0), ts(1), Some(remoteId))
        found2 <- store.searchComputedBetween(ts(0), ts(2))
        found3 <- store.searchComputedBetween(ts(1), ts(1))
        found4 <- store.searchComputedBetween(ts(0), ts(0))
      } yield {
        found1.toSet shouldBe Set((period(0, 1), remoteId, dummyCommitment))
        found2.toSet shouldBe Set(
          (period(0, 1), remoteId, dummyCommitment),
          (period(1, 2), remoteId, dummyCommitment),
          (period(1, 2), remoteId2, dummyCommitment),
        )
        found3.toSet shouldBe Set((period(0, 1), remoteId, dummyCommitment))
        found4.toSet shouldBe Set.empty
      }
    }

    "correctly search stored remote commitment messages" in {
      val store = mk()

      val dummyMsg2 = AcsCommitment.create(
        domainId,
        remoteId,
        localId,
        period(2, 3),
        dummyCommitment,
        defaultProtocolVersion,
      )
      val dummySigned2 = SignedProtocolMessage(dummyMsg2, dummySignature)
      val dummyMsg3 = AcsCommitment.create(
        domainId,
        remoteId2,
        localId,
        period(0, 1),
        dummyCommitment,
        defaultProtocolVersion,
      )
      val dummySigned3 = SignedProtocolMessage(dummyMsg3, dummySignature)

      for {
        _ <- store.storeReceived(dummySigned)
        _ <- store.storeReceived(dummySigned2)
        _ <- store.storeReceived(dummySigned3)
        found1 <- store.searchReceivedBetween(ts(0), ts(1))
        found2 <- store.searchReceivedBetween(ts(0), ts(1), Some(remoteId))
      } yield {
        found1.toSet shouldBe Set(dummySigned, dummySigned3)
        found2.toSet shouldBe Set(dummySigned)
      }
    }

    "allow storing different remote commitment messages for the same period" in {
      val store = mk()

      val dummyMsg2 = AcsCommitment.create(
        domainId,
        remoteId,
        localId,
        period(0, 1),
        dummyCommitment2,
        defaultProtocolVersion,
      )
      val dummySigned2 = SignedProtocolMessage(dummyMsg2, dummySignature)

      for {
        _ <- store.storeReceived(dummySigned)
        _ <- store.storeReceived(dummySigned2)
        found1 <- store.searchReceivedBetween(ts(0), ts(1))
      } yield {
        found1.toSet shouldBe Set(dummySigned, dummySigned2)
      }
    }

    "be idempotent when storing the same remote commitment messages for the same period" in {
      val store = mk()

      for {
        _ <- store.storeReceived(dummySigned)
        _ <- store.storeReceived(dummySigned)
        found1 <- store.searchReceivedBetween(ts(0), ts(1))
      } yield {
        found1.toList shouldBe List(dummySigned)
      }
    }

    "be idempotent when storing the same computed commitment messages" in {
      val store = mk()

      for {
        _ <- store.storeComputed(period(0, 1), remoteId, dummyCommitment)
        _ <- store.storeComputed(period(0, 1), remoteId, dummyCommitment)
        found1 <- store.searchComputedBetween(ts(0), ts(1))
      } yield {
        found1.toList shouldBe List((period(0, 1), remoteId, dummyCommitment))
      }
    }

    "fails when storing different computed commitments for the same period and counter participant" in {
      val store = mk()

      loggerFactory.suppressWarningsAndErrors {
        recoverToSucceededIf[Throwable] {
          for {
            _ <- store.storeComputed(period(0, 1), remoteId, dummyCommitment)
            _ <- store.storeComputed(period(0, 1), remoteId, dummyCommitment2)
          } yield ()
        }
      }
    }

    "compute reasonable clean periods before on small examples" in {
      val beforeOrAt = ts(20)

      def times(i: Integer, j: Integer) = ts(i) -> ts(j)
      val uncleanPeriodsWithResults = List(
        List() -> ts(20),
        List(times(0, 5)) -> ts(20),
        List(times(0, 5), times(0, 5)) -> ts(20),
        List(times(15, 20)) -> ts(15),
        List(times(0, 5), times(15, 20)) -> ts(15),
        List(times(5, 15), times(15, 20)) -> ts(5),
        List(times(10, 15), times(5, 10), times(15, 20)) -> ts(5),
        List(times(5, 15), times(5, 10), times(10, 15), times(10, 15), times(15, 20)) -> ts(5),
        List(times(5, 15), times(10, 15), times(10, 15), times(15, 20), times(5, 10)) -> ts(5),
        List(times(0, 5), times(5, 10), times(15, 20)) -> ts(15),
        List(times(0, 5), times(20, 25)) -> ts(20),
        List(times(15, 20), times(20, 25)) -> ts(15),
        List(times(0, 20)) -> ts(0),
        List(times(15, 20), times(5, 15), times(0, 5)) -> ts(0),
        List(times(0, 5), times(5, 10), times(10, 15), times(15, 20)) -> ts(0),
        List(times(0, 5), times(5, 10), times(15, 20), times(10, 15)) -> ts(0),
        List(times(0, 10), times(10, 20)) -> ts(0),
        List(times(0, 10), times(0, 5), times(5, 10), times(15, 20), times(10, 20)) -> ts(0),
        List(times(25, 30)) -> ts(20),
        List(times(0, 10), times(25, 30)) -> ts(20),
        List(times(5, 15), times(10, 20), times(25, 30)) -> ts(5),
      )

      forAll(uncleanPeriodsWithResults) { case (uncleans, expected) =>
        AcsCommitmentStore.latestCleanPeriod(beforeOrAt, uncleans) shouldBe expected
      }
    }

    "can tolerate overlapping outstanding periods" in {
      val store = mk()

      for {
        _ <- store.markOutstanding(period(0, 1), Set(remoteId))
        _ <- store.markOutstanding(period(0, 2), Set(remoteId))
        _ <- store.markSafe(remoteId, period(1, 2))
        outstandingWithId <- store.outstanding(ts(0), ts(2), Some(remoteId))
        outstandingWithoutId <- store.outstanding(ts(0), ts(2), None)
      } yield {
        outstandingWithId.toSet shouldBe Set(period(0, 1) -> remoteId)
        outstandingWithoutId.toSet shouldBe Set(period(0, 1) -> remoteId)
      }
    }
  }

}

trait IncrementalCommitmentStoreTest extends CommitmentStoreBaseTest {
  import com.digitalasset.canton.lfPartyOrdering

  def commitmentSnapshotStore(mkWith: ExecutionContext => IncrementalCommitmentStore): Unit = {

    def mk() = mkWith(executionContext)

    def rt(timestamp: Int, tieBreaker: Int) = RecordTime(ts(timestamp), tieBreaker.toLong)

    "give correct snapshots on a small example" in {
      val snapshot = mk()

      val snapAB10 = ByteString.copyFromUtf8("AB10")
      val snapBC10 = ByteString.copyFromUtf8("BC10")
      val snapBC11 = ByteString.copyFromUtf8("BC11")
      val snapAB2 = ByteString.copyFromUtf8("AB21")
      val snapAC2 = ByteString.copyFromUtf8("AC21")

      for {
        res0 <- snapshot.get()
        wm0 <- snapshot.watermark

        _ <- snapshot.update(
          rt(1, 0),
          updates = Map(SortedSet(alice, bob) -> snapAB10, SortedSet(bob, charlie) -> snapBC10),
          deletes = Set.empty,
        )
        res1 <- snapshot.get()
        wm1 <- snapshot.watermark

        _ <- snapshot.update(
          rt(1, 1),
          updates = Map(SortedSet(bob, charlie) -> snapBC11),
          deletes = Set.empty,
        )
        res11 <- snapshot.get()
        wm11 <- snapshot.watermark

        _ <- snapshot.update(
          rt(2, 0),
          updates = Map(SortedSet(alice, bob) -> snapAB2, SortedSet(alice, charlie) -> snapAC2),
          deletes = Set(SortedSet(bob, charlie)),
        )
        res2 <- snapshot.get()
        ts2 <- snapshot.watermark

        _ <- snapshot.update(
          rt(3, 0),
          updates = Map.empty,
          deletes = Set(SortedSet(alice, bob), SortedSet(alice, charlie)),
        )
        res3 <- snapshot.get()
        ts3 <- snapshot.watermark

      } yield {
        wm0 shouldBe RecordTime.MinValue
        res0 shouldBe (RecordTime.MinValue -> Map.empty)

        wm1 shouldBe rt(1, 0)
        res1 shouldBe (rt(1, 0) -> Map(
          SortedSet(alice, bob) -> snapAB10,
          SortedSet(bob, charlie) -> snapBC10,
        ))

        wm11 shouldBe rt(1, 1)
        res11 shouldBe (rt(1, 1) -> Map(
          SortedSet(alice, bob) -> snapAB10,
          SortedSet(bob, charlie) -> snapBC11,
        ))

        ts2 shouldBe rt(2, 0)
        res2 shouldBe (rt(2, 0) -> Map(
          SortedSet(alice, bob) -> snapAB2,
          SortedSet(alice, charlie) -> snapAC2,
        ))

        ts3 shouldBe rt(3, 0)
        res3 shouldBe (rt(3, 0) -> Map.empty)
      }
    }
  }

}

trait CommitmentQueueTest extends CommitmentStoreBaseTest {

  def commitmentQueue(mkWith: ExecutionContext => CommitmentQueue): Unit = {
    def mk(): CommitmentQueue = mkWith(executionContext)
    def commitment(
        remoteId: ParticipantId,
        start: Int,
        end: Int,
        cmt: AcsCommitment.CommitmentType,
    ) =
      AcsCommitment(
        domainId,
        remoteId,
        localId,
        CommitmentPeriod(ts(start), ts(end), PositiveSeconds.ofSeconds(5)).value,
        cmt,
      )(ProtocolVersion.latestForTest, None)

    "work sensibly in a basic scenario" in {
      val queue = mk()
      val c11 = commitment(remoteId, 0, 5, dummyCommitment)
      val c12 = commitment(remoteId2, 0, 5, dummyCommitment2)
      val c21 = commitment(remoteId, 5, 10, dummyCommitment)
      val c22 = commitment(remoteId2, 5, 10, dummyCommitment2)
      val c31 = commitment(remoteId, 10, 15, dummyCommitment)
      val c32 = commitment(remoteId2, 10, 15, dummyCommitment2)
      val c41 = commitment(remoteId, 15, 20, dummyCommitment)

      for {
        _ <- queue.enqueue(c11)
        _ <- queue.enqueue(c11) // Idempotent enqueue
        _ <- queue.enqueue(c12)
        _ <- queue.enqueue(c21)
        at5 <- queue.peekThrough(ts(5))
        at10 <- queue.peekThrough(ts(10))
        _ <- queue.enqueue(c22)
        at10with22 <- queue.peekThrough(ts(10))
        _ <- queue.enqueue(c32)
        at10with32 <- queue.peekThrough(ts(10))
        at15 <- queue.peekThrough(ts(15))
        _ <- queue.deleteThrough(ts(5))
        at15AfterDelete <- queue.peekThrough(ts(15))
        _ <- queue.enqueue(c31)
        at15with31 <- queue.peekThrough(ts(15))
        _ <- queue.deleteThrough(ts(15))
        at20AfterDelete <- queue.peekThrough(ts(20))
        _ <- queue.enqueue(c41)
        at20with41 <- queue.peekThrough(ts(20))
      } yield {
        // We don't really care how the priority queue breaks the ties, so just use sets here
        at5.toSet shouldBe Set(c11, c12)
        at10.toSet shouldBe Set(c11, c12, c21)
        at10with22.toSet shouldBe Set(c11, c12, c21, c22)
        at10with32.toSet shouldBe Set(c11, c12, c21, c22)
        at15.toSet shouldBe Set(c11, c12, c21, c22, c32)
        at15AfterDelete.toSet shouldBe Set(c21, c22, c32)
        at15with31.toSet shouldBe Set(c21, c22, c32, c31)
        at20AfterDelete shouldBe List.empty
        at20with41 shouldBe List(c41)
      }
    }
  }
}
