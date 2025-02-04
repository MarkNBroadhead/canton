// Copyright (c) 2022 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.digitalasset.canton.participant.store

import cats.data.EitherT
import cats.kernel.Order
import com.daml.lf.data.Ref.PackageId
import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.logging.pretty.{Pretty, PrettyPrinting}
import com.digitalasset.canton.participant.RequestCounter
import com.digitalasset.canton.participant.store.ActiveContractSnapshot.ActiveContractIdsChange
import com.digitalasset.canton.participant.store.ActiveContractStore.AcsError
import com.digitalasset.canton.participant.util.{StateChange, TimeOfChange}
import com.digitalasset.canton.protocol.LfContractId
import com.digitalasset.canton.topology.DomainId
import com.digitalasset.canton.tracing.TraceContext
import com.digitalasset.canton.util.CheckedT
import com.google.common.annotations.VisibleForTesting

import scala.collection.immutable.SortedMap
import scala.concurrent.Future

/** <p>The active contract store (ACS) stores for every contract ID
  * whether it is inexistent, [[ActiveContractStore.Active]],
  * [[ActiveContractStore.Archived]], or [[ActiveContractStore.TransferredAway]],
  * along with the timestamp of the latest change.
  * Every change is associated with the timestamp and request counter of the request that triggered the change.
  * The changes are ordered first by timestamp, then by request counter, and finally by change type (activations before deactivations).
  * Implementations must be thread-safe.
  * Updates must be idempotent.</p>
  *
  * <p>Creations, transfers, and archivals can be mixed arbitrarily.
  * A contract may be transferred in and out several times during its lifetime.
  * It becomes active with every transfer-in and transferred away with every transfer-out.
  * If the ACS detects irregularities, the change method reports them.</p>
  *
  * <p>These methods are supposed to be called by the [[com.digitalasset.canton.participant.protocol.conflictdetection.ConflictDetector]] only,
  * which coordinates the request journal updates and the updates to the ACS.</p>
  *
  * <p>Updates may be written asynchronously.
  * Every implementation determines an order over all the changes and queries to the ACS.
  * Each individual change must be applied atomically and the result is determined with respect to this order.
  * This order need not be consistent with the order of the calls, though.
  * However, the following is guaranteed:
  * If the future returned by a call completes and observing the completion happens before another call,
  * then all changes of the former call must be ordered before all changes of the later call.</p>
  *
  * <p>Bulk methods like [[ActiveContractStore.createContracts]] and [[ActiveContractStore.archiveContracts]]
  * generate one individual change for each contract.
  * So their changes may be interleaved with other calls.</p>
  *
  * @see ActiveContractSnapshot for the ACS snapshot interface
  */
trait ActiveContractStore
    extends ActiveContractSnapshot
    with ConflictDetectionStore[LfContractId, ActiveContractStore.Status, AcsError] {
  import ActiveContractStore._

  /** Marks the given contracts as active from `timestamp` (inclusive) onwards.
    *
    * @param contractIds The contract IDs of the created contracts
    * @param toc The time of change consisting of
    *            <ul>
    *              <li>The request counter of the confirmation request that created the contracts</li>
    *              <li>The timestamp of the confirmation request that created the contracts.</li>
    *            </ul>
    * @return The future completes when all contract states have been updated.
    *         The following irregularities are reported for each contract:
    *         <ul>
    *           <li>[[ActiveContractStore.DoubleContractCreation]] if the contract is created a second time.</li>
    *           <li>[[ActiveContractStore.SimultaneousActivation]] if the contract is transferred in at the same time
    *             or has been created by a different request at the same time.</li>
    *           <li>[[ActiveContractStore.ChangeBeforeCreation]] for every change that occurs before the creation timestamp.
    *             This is reported only if no [[ActiveContractStore.DoubleContractCreation]] is reported.</li>
    *           <li>[[ActiveContractStore.ChangeAfterArchival]] if this creation is later than the earliest archival of the contract.</li>
    *         </ul>
    */
  def createContracts(contractIds: Seq[LfContractId], toc: TimeOfChange)(implicit
      traceContext: TraceContext
  ): CheckedT[Future, AcsError, AcsWarning, Unit]

  /** Shorthand for `createContracts(Seq(contractId), toc)` */
  def createContract(contractId: LfContractId, toc: TimeOfChange)(implicit
      traceContext: TraceContext
  ): CheckedT[Future, AcsError, AcsWarning, Unit] =
    createContracts(Seq(contractId), toc)

  /** Marks the given contracts as archived from `toc`'s timestamp (inclusive) onwards.
    *
    * @param contractIds The contract IDs of the contracts to be archived
    * @param toc The time of change consisting of
    *            <ul>
    *              <li>The request counter of the confirmation request that archives the contracts.</li>
    *              <li>The timestamp on the confirmation request that archives the contracts.</li>
    *            </ul>
    * @return The future completes when all contract states have been updated.
    *         The following irregularities are reported for each contract:
    *         <ul>
    *           <li>[[ActiveContractStore.DoubleContractArchival]] if the contract is archived a second time.</li>
    *           <li>[[ActiveContractStore.SimultaneousDeactivation]] if the contract is transferred out at the same time
    *             or has been archived by a different request at the same time.</li>
    *           <li>[[ActiveContractStore.ChangeAfterArchival]] for every change that occurs after the archival timestamp.
    *             This is reported only if no [[ActiveContractStore.DoubleContractArchival]] is reported.</li>
    *           <li>[[ActiveContractStore.ChangeBeforeCreation]] if this archival is earlier than the latest creation of the contract.</li>
    *         </ul>
    */
  def archiveContracts(contractIds: Seq[LfContractId], toc: TimeOfChange)(implicit
      traceContext: TraceContext
  ): CheckedT[Future, AcsError, AcsWarning, Unit]

  /** Shorthand for `archiveContracts(Seq(contractId), toc)` */
  def archiveContract(contractId: LfContractId, toc: TimeOfChange)(implicit
      traceContext: TraceContext
  ): CheckedT[Future, AcsError, AcsWarning, Unit] =
    archiveContracts(Seq(contractId), toc)

  /** Returns the latest [[com.digitalasset.canton.participant.store.ActiveContractStore.Status]]
    * for the given contract IDs along with its [[com.digitalasset.canton.participant.util.TimeOfChange]].
    *
    * This method is used by the protocol processors for conflict detection.
    * In-flight transactions may have changesets not yet written to the ACS datastore.
    * Since only the [[com.digitalasset.canton.participant.protocol.conflictdetection.ConflictDetector]] tracks in-flight changesets,
    * this method cannot be used as a source of valid data to other components.
    *
    * If a contract is created or transferred-in and archived or transferred-out at the same [[com.digitalasset.canton.participant.util.TimeOfChange]],
    * the contract is [[ActiveContractStore.Archived]] or [[ActiveContractStore.TransferredAway]].
    * A contract cannot be archived and transferred out at the same timestamp.
    *
    * @return The map from contracts in `contractIds` in the store to their latest state.
    *         Nonexistent contracts are excluded from the map.
    * @see ActiveContractSnapshot!.snapshot
    */
  def fetchStates(contractIds: Iterable[LfContractId])(implicit
      traceContext: TraceContext
  ): Future[Map[LfContractId, ContractState]]

  /** Marks the given contracts as transferred in from `toc`'s timestamp (inclusive) onwards.
    *
    * @param transferIns The contract IDs to transfer in and their origin domains.
    * @param toc The time of change consisting of
    *            <ul>
    *              <li>The request counter of the transfer-in request.</li>
    *              <li>The timestamp on the transfer-in request.</li>
    *            </ul>
    * @return The future completes when the contract states have been updated.
    *         The following irregularities are reported:
    *         <ul>
    *           <li>[[ActiveContractStore.SimultaneousActivation]] if a transfer-in from another domain or a creation
    *             has been added with the same timestamp.</li>
    *           <li>[[ActiveContractStore.ChangeAfterArchival]] if this timestamp is after the earliest archival of the contract.</li>
    *           <li>[[ActiveContractStore.ChangeBeforeCreation]] if this timestamp is before the latest creation of the contract.</li>
    *         </ul>
    */
  def transferInContracts(transferIns: Seq[(LfContractId, DomainId)], toc: TimeOfChange)(implicit
      traceContext: TraceContext
  ): CheckedT[Future, AcsError, AcsWarning, Unit]

  def transferInContract(contractId: LfContractId, toc: TimeOfChange, originDomain: DomainId)(
      implicit traceContext: TraceContext
  ): CheckedT[Future, AcsError, AcsWarning, Unit] =
    transferInContracts(Seq((contractId, originDomain)), toc)

  /** Marks the given contracts as [[ActiveContractStore.TransferredAway]] from `toc`'s timestamp (inclusive) onwards.
    *
    * @param transferOuts The contract IDs to transfer out and their target domains.
    * @param toc The time of change consisting of
    *            <ul>
    *              <li>The request counter of the transfer-out request.</li>
    *              <li>The timestamp on the transfer-out request.</li>
    *            </ul>
    * @return The future completes when the contract state has been updated.
    *         The following irregularities are reported:
    *         <ul>
    *           <li>[[ActiveContractStore.SimultaneousDeactivation]] if a transfer-out to another domain or a creation
    *             has been added with the same timestamp.</li>
    *           <li>[[ActiveContractStore.ChangeAfterArchival]] if this timestamp is after the earliest archival of the contract.</li>
    *           <li>[[ActiveContractStore.ChangeBeforeCreation]] if this timestamp is before the latest creation of the contract.</li>
    *         </ul>
    */
  def transferOutContracts(transferOuts: Seq[(LfContractId, DomainId)], toc: TimeOfChange)(implicit
      traceContext: TraceContext
  ): CheckedT[Future, AcsError, AcsWarning, Unit]

  def transferOutContract(contractId: LfContractId, toc: TimeOfChange, targetDomain: DomainId)(
      implicit traceContext: TraceContext
  ): CheckedT[Future, AcsError, AcsWarning, Unit] =
    transferOutContracts(Seq((contractId, targetDomain)), toc)

  /** Deletes all entries about archived contracts whose status hasn't changed after the timestamp.
    *
    * The caller must ensure that the given timestamp is at most the one of the clean cursor in the
    * [[com.digitalasset.canton.participant.protocol.RequestJournal]]
    */
  override protected[canton] def doPrune(beforeAndIncluding: CantonTimestamp)(implicit
      traceContext: TraceContext
  ): EitherT[Future, AcsError, Unit]

  /** Deletes all activeness changes from requests whose request counter is at least the given one.
    * This method must not be called concurrently with creating, archiving, or transferring contracts.
    *
    * Therefore, this method need not be linearizable w.r.t. creating, archiving, or transferring contracts.
    * For example, if a request `rc1` creates a contract `c` and another request `rc2` archives it
    * while [[deleteSince]] is running for some `rc <= rc1, rc2`, then there are no guarantees
    * which of the effects of `rc1` and `rc2` remain. For example, `c` could end up being inexistent, active, or
    * archived but never created, even if the writes for `rc1` and `rc2` are successful.
    */
  def deleteSince(criterion: RequestCounter)(implicit traceContext: TraceContext): Future[Unit]

  /** Returns the total number of contracts whose states are stored at the given timestamp.
    *
    * To get a consistent snapshot, the caller must ensure that the timestamp specifies a time that
    * is not in the future, i.e., not after the timestamp of the clean cursor in the
    * [[com.digitalasset.canton.participant.protocol.RequestJournal]]
    * Note that the result may change between two invocations if [[prune]] is called in the meantime.
    */
  @VisibleForTesting
  private[participant] def contractCount(timestamp: CantonTimestamp)(implicit
      traceContext: TraceContext
  ): Future[Int]
}

object ActiveContractStore {

  type ContractState = StateChange[Status]
  val ContractState: StateChange.type = StateChange

  sealed trait ActivenessChangeDetail extends Product with Serializable {
    def unwrap: Option[DomainId]
    def isTransfer: Boolean
  }
  case class TransferDetail(domain: DomainId) extends ActivenessChangeDetail {
    override def unwrap: Option[DomainId] = Some(domain)
    override def isTransfer: Boolean = true
  }
  case object CreationArchivalDetail extends ActivenessChangeDetail {
    override def unwrap: None.type = None
    override def isTransfer: Boolean = false
  }
  type CreationArchivalDetail = CreationArchivalDetail.type
  object ActivenessChangeDetail {
    def apply(remoteDomain: Option[DomainId]): ActivenessChangeDetail =
      remoteDomain.fold[ActivenessChangeDetail](CreationArchivalDetail)(TransferDetail)

    private[store] implicit val orderForActivenessChangeDetail: Order[ActivenessChangeDetail] =
      Order.by[ActivenessChangeDetail, Option[DomainId]](_.unwrap)
  }

  sealed trait AcsBaseError extends Product with Serializable

  /** Warning cases returned by the operations on the [[ActiveContractStore!]] */
  sealed trait AcsWarning extends AcsBaseError

  /** Error cases returned by the operations on the [[ActiveContractStore!]] */
  trait AcsError extends AcsBaseError

  /** A contract is simultaneously created and/or transferred from possibly several origin domains */
  case class SimultaneousActivation(
      contractId: LfContractId,
      toc: TimeOfChange,
      detail1: ActivenessChangeDetail,
      detail2: ActivenessChangeDetail,
  ) extends AcsWarning

  /** A contract is simultaneously archived and/or transferred out to possibly several origin domains */
  case class SimultaneousDeactivation(
      contractId: LfContractId,
      toc: TimeOfChange,
      detail1: ActivenessChangeDetail,
      detail2: ActivenessChangeDetail,
  ) extends AcsWarning

  /** The given contract is archived a second time, but with a different time of change. */
  case class DoubleContractArchival(
      contractId: LfContractId,
      oldTime: TimeOfChange,
      newTime: TimeOfChange,
  ) extends AcsWarning

  /** The given contract is created a second time, but with a different time of change. */
  case class DoubleContractCreation(
      contractId: LfContractId,
      oldTime: TimeOfChange,
      newTime: TimeOfChange,
  ) extends AcsWarning

  /** The state of a contract is changed before its `creation`. */
  case class ChangeBeforeCreation(
      contractId: LfContractId,
      creation: TimeOfChange,
      change: TimeOfChange,
  ) extends AcsWarning

  /** The state of a contract is changed after its `archival`. */
  case class ChangeAfterArchival(
      contractId: LfContractId,
      archival: TimeOfChange,
      change: TimeOfChange,
  ) extends AcsWarning

  /** Status of a contract in the ACS */
  sealed trait Status extends Product with Serializable with PrettyPrinting with HasPrunable {

    /** Returns whether pruning may delete a contract in this state */
    override def prunable: Boolean
  }

  /** The contract has been created and is active. */
  case object Active extends Status {
    override def prunable: Boolean = false
    override def pretty: Pretty[Active.type] = prettyOfObject[Active.type]
  }

  /** The contract has been archive and it is not active. */
  case object Archived extends Status {
    override def prunable: Boolean = true
    override def pretty: Pretty[Archived.type] = prettyOfObject[Archived.type]
  }

  /** The contract has been transferred out to the given `targetDomain` after it had resided on this domain.
    * It does not reside on the current domain, but the contract has existed at some time.
    *
    * In particular, this state does not imply any of the following:
    * <ul>
    *   <li>The transfer was completed on the target domain.</li>
    *   <li>The contract now resides on the target domain.</li>
    *   <li>The contract is active or archived on any other domain.</li>
    * </ul>
    */
  case class TransferredAway(targetDomain: DomainId) extends Status {
    override def prunable: Boolean = true
    override def pretty: Pretty[TransferredAway] = prettyOfClass(unnamedParam(_.targetDomain))
  }
}

/** Provides snapshotting for active contracts. */
trait ActiveContractSnapshot {

  /** Returns all contracts that were active right after the given timestamp,
    * and when the contract became active for the last time before or at the given timestamp.
    *
    * @param timestamp The timestamp at which the snapshot shall be taken.
    *                  Must be before the timestamp that corresponds to the head cursor in the
    *                  [[com.digitalasset.canton.participant.protocol.RequestJournal]] for the state
    *                  [[com.digitalasset.canton.participant.protocol.RequestJournal.RequestState.Clean]].
    *                  If this precondition is violated, the returned snapshot may be inconsistent, i.e.,
    *                  it may omit some contracts that were [[ActiveContractStore.Active]] at the given time
    *                  and it may include contracts that were actually [[ActiveContractStore.Archived]] or
    *                  [[ActiveContractStore.TransferredAway]].
    * @return A map from contracts to the latest timestamp (no later than the given `timestamp`)
    *         when they became active again.
    *         It contains exactly those contracts that were active right after the given timestamp.
    *         If a contract is created or transferred-in and archived or transferred-out at the same timestamp,
    *         it does not show up in any snapshot.
    *         The map is sorted by [[cats.kernel.Order]]`[`[[com.digitalasset.canton.protocol.LfContractId]]`]`.
    */
  def snapshot(timestamp: CantonTimestamp)(implicit
      traceContext: TraceContext
  ): Future[Either[AcsError, SortedMap[LfContractId, CantonTimestamp]]]

  /** Returns Some(contractId) if an active contract belonging to package `pkg` exists, otherwise returns None.
    * The returned contractId may be any active contract from package `pkg`.
    * The most recent contract state is used.
    */
  def packageUsage(pkg: PackageId, contractStore: ContractStore)(implicit
      traceContext: TraceContext
  ): Future[Option[(LfContractId)]]

  /** Returns a map to the timestamp when the contract became active for the last time before or at the given timestamp.
    * Omits contracts that not active right after the given timestamp.
    *
    * @param timestamp The timestamp at which the activeness of the contracts shall be determined.
    *                  Must be before the timestamp that corresponds to the head cursor in the
    *                  [[com.digitalasset.canton.participant.protocol.RequestJournal]] for the state
    *                  [[com.digitalasset.canton.participant.protocol.RequestJournal.RequestState.Clean]].
    *                  If this precondition is violated, the returned snapshot may be inconsistent, i.e.,
    *                  it may omit some contracts that were [[ActiveContractStore.Active]] at the given time
    *                  and it may include contracts that were actually [[ActiveContractStore.Archived]] or
    *                  [[ActiveContractStore.TransferredAway]].
    */
  def contractSnapshot(contractIds: Set[LfContractId], timestamp: CantonTimestamp)(implicit
      traceContext: TraceContext
  ): EitherT[Future, AcsError, Map[LfContractId, CantonTimestamp]]

  /** Returns all changes to the active contract set between the two timestamps (both inclusive)
    * in the order of their changes.
    * The provided lower bound must not be larger than the upper bound.
    *
    * @param fromExclusive The lower bound for the changes. Must not be larger than the upper bound.
    * @param toInclusive The upper bound for the changes. Must not be smaller than the lower bound.
    *
    * @throws java.lang.IllegalArgumentException If the intervals are in the wrong order.
    */
  def changesBetween(fromExclusive: TimeOfChange, toInclusive: TimeOfChange)(implicit
      traceContext: TraceContext
  ): Future[LazyList[(TimeOfChange, ActiveContractIdsChange)]]

}

object ActiveContractSnapshot {

  case class ActiveContractIdsChange(
      activations: Set[LfContractId],
      deactivations: Set[LfContractId],
  )

  object ActiveContractIdsChange {
    val empty = ActiveContractIdsChange(Set.empty, Set.empty)
  }

}
