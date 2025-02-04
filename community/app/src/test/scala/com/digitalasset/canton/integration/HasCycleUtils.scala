// Copyright (c) 2022 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.digitalasset.canton.integration

import com.digitalasset.canton.admin.api.client.commands.LedgerApiTypeWrappers
import com.digitalasset.canton.console.ParticipantReference
import com.digitalasset.canton.environment.Environment
import com.digitalasset.canton.topology.PartyId
import com.digitalasset.canton.participant.admin.workflows.{PingPong => M}

/** Adds the ability to run pingpong cycles to integration tests */
trait HasCycleUtils[E <: Environment, TCE <: TestConsoleEnvironment[E]] {
  this: BaseIntegrationTest[E, TCE] =>

  /** @param partyId assumes that the party is hosted on participant1 AND participant2 (in the simplest case
    * this could simply mean that participant1 == participant2)
    */
  def runCycle(
      partyId: PartyId,
      participant1: ParticipantReference,
      participant2: ParticipantReference,
      commandId: String = "",
  ): Unit = {

    def p2acs(): Seq[LedgerApiTypeWrappers.WrappedCreatedEvent] =
      participant2.ledger_api.acs.of_party(partyId).filter(_.templateId == "PingPong.Cycle")

    p2acs() shouldBe empty

    createCycleContract(participant1, partyId, "I SHALL CREATE", commandId)
    val coid = participant2.ledger_api.acs.await(partyId, M.Cycle)
    val cycleEx = coid.contractId.exerciseVoid(partyId.toPrim).command
    participant2.ledger_api.commands.submit(
      Seq(partyId),
      Seq(cycleEx),
      commandId = (if (commandId.isEmpty) "" else s"$commandId-response"),
    )
    eventually() {
      p2acs() shouldBe empty
    }
  }

  def createCycleContract(
      participant: ParticipantReference,
      partyId: PartyId,
      id: String,
      commandId: String = "",
  ): Unit = {

    val cycle = M.Cycle(owner = partyId.toPrim, id = id).create.command
    participant.ledger_api.commands.submit(Seq(partyId), Seq(cycle), commandId = commandId)

  }
}
