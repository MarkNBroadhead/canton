// Copyright (c) 2022 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.digitalasset

import com.daml.ledger.configuration
import com.daml.lf.command.ReplayCommand
import com.daml.lf.data.{IdString, Ref, Time}
import com.daml.lf.transaction.Versioned
import com.daml.lf.value.Value

import scala.annotation.nowarn

package object canton {

  // Lf type for other ledger scalars, e.g. application, command and workflow id
  // LfLedgerString has a length limit of 255 characters and contains alphanumeric characters and an itemized set of
  // separators including _, :, - and even spaces
  type LfLedgerString = Ref.LedgerString
  val LfLedgerString: Ref.LedgerString.type = Ref.LedgerString

  // A party identifier representation in LF. See [[com.digitalasset.canton.topology.PartyId]] for the party identifier
  // in Canton.
  type LfPartyId = Ref.Party
  val LfPartyId: Ref.Party.type = Ref.Party

  // Ledger participant id
  type LedgerParticipantId = Ref.ParticipantId
  val LedgerParticipantId: Ref.ParticipantId.type = Ref.ParticipantId

  // Ledger submission id
  type LedgerSubmissionId = Ref.SubmissionId
  val LedgerSubmissionId: Ref.SubmissionId.type = Ref.SubmissionId

  // Ledger application id
  type LedgerApplicationId = Ref.ApplicationId
  val LedgerApplicationId: Ref.ApplicationId.type = Ref.ApplicationId

  // Ledger configuration
  type LedgerConfiguration = configuration.Configuration
  val LedgerConfiguration: configuration.Configuration.type = configuration.Configuration

  // Ledger transaction id
  type LedgerTransactionId = Ref.TransactionId
  val LedgerTransactionId: Ref.TransactionId.type = Ref.TransactionId

  // Exercise choice name
  type LfChoiceName = Ref.ChoiceName
  val LfChoiceName: Ref.ChoiceName.type = Ref.ChoiceName

  type LfPackageId = Ref.PackageId
  val LfPackageId: Ref.PackageId.type = Ref.PackageId

  // Timestamp used by lf and sync api
  type LfTimestamp = Time.Timestamp
  val LfTimestamp: Time.Timestamp.type = Time.Timestamp

  type LfValue = Value
  val LfValue: Value.type = Value

  type LfVersioned[T] = Versioned[T]
  val LfVersioned: Versioned.type = Versioned

  // Lf commands for use by lf engine.reinterpret
  type LfCommand = ReplayCommand
  val LfCommand: ReplayCommand.type = ReplayCommand

  type LfCreateCommand = LfCommand.Create
  val LfCreateCommand: LfCommand.Create.type = LfCommand.Create

  @nowarn("cat=deprecation")
  type LfExerciseCommand = LfCommand.LenientExercise
  val LfExerciseCommand: LfCommand.LenientExercise.type = LfCommand.LenientExercise

  type LfExerciseByKeyCommand = LfCommand.ExerciseByKey
  val LfExerciseByKeyCommand: LfCommand.ExerciseByKey.type = LfCommand.ExerciseByKey

  type LfFetchCommand = LfCommand.Fetch
  val LfFetchCommand: LfCommand.Fetch.type = LfCommand.Fetch

  type LfFetchByKeyCommand = LfCommand.FetchByKey
  val LfFetchByKeyCommand: LfCommand.FetchByKey.type = LfCommand.FetchByKey

  type LfLookupByKeyCommand = LfCommand.LookupByKey
  val LfLookupByKeyCommand: LfCommand.LookupByKey.type = LfCommand.LookupByKey

  /** The counter assigned by the sequencer to messages sent to the participant.
    * The counter is specific to every participant.
    */
  type SequencerCounter = Long

  /** The [[SequencerCounter]] used for the first message from a sequencer to a participant */
  val GenesisSequencerCounter: SequencerCounter = 0L

  /** Wrap a method call with this method to document that the caller is sure that the callee's preconditions are met. */
  def checked[A](x: => A): A = x

  /** Evaluate the expression and discard the result. */
  implicit class DiscardOps[A](a: A) {
    @nowarn("cat=unused")
    def discard[B](implicit ev: A =:= B): Unit = ()
  }

  implicit val lfPartyOrdering: Ordering[LfPartyId] =
    IdString.`Party order instance`.toScalaOrdering
}
