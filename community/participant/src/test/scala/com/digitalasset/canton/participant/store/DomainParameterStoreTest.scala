// Copyright (c) 2022 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.digitalasset.canton.participant.store

import com.digitalasset.canton.topology.{DomainId, UniqueIdentifier}
import com.digitalasset.canton.protocol.TestDomainParameters
import com.digitalasset.canton.config.RequireTypes.NonNegativeInt
import com.digitalasset.canton.BaseTest
import org.scalatest.wordspec.AsyncWordSpec

trait DomainParameterStoreTest { this: AsyncWordSpec with BaseTest =>

  val domainId = DomainId(UniqueIdentifier.tryFromProtoPrimitive("domainId::domainId"))
  val defaultParams = TestDomainParameters.defaultStatic

  def domainParameterStore(mk: DomainId => DomainParameterStore): Unit = {

    "setParameters" should {
      "store new parameters" in {
        val store = mk(domainId)
        val params = defaultParams
        for {
          _ <- store.setParameters(params)
          last <- store.lastParameters
        } yield {
          last shouldBe Some(params)
        }
      }

      "be idempotent" in {
        val store = mk(domainId)
        val params = defaultParams.copy(maxRatePerParticipant = NonNegativeInt.tryCreate(100))
        for {
          _ <- store.setParameters(params)
          _ <- store.setParameters(params)
          last <- store.lastParameters
        } yield {
          last shouldBe Some(params)
        }
      }

      "not overwrite changed domain parameters" in {
        val store = mk(domainId)
        val params = defaultParams
        val modified = params.copy(maxInboundMessageSize = params.maxInboundMessageSize.tryAdd(1))
        for {
          _ <- store.setParameters(params)
          ex <- store.setParameters(modified).failed
          last <- store.lastParameters
        } yield {
          ex shouldBe an[IllegalArgumentException]
          last shouldBe Some(params)
        }
      }
    }

    "lastParameters" should {
      "return None for the empty store" in {
        val store = mk(domainId)
        for {
          last <- store.lastParameters
        } yield {
          last shouldBe None
        }
      }
    }
  }
}
