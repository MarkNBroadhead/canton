// Copyright (c) 2022 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.digitalasset.canton.participant.store.db

import com.digitalasset.canton.config.BatchAggregatorConfig
import com.digitalasset.canton.config.RequireTypes.PositiveNumeric
import com.digitalasset.canton.topology.{DomainId, UniqueIdentifier}
import com.digitalasset.canton.participant.store.RequestJournalStoreTest
import com.digitalasset.canton.resource.DbStorage
import com.digitalasset.canton.store.IndexedDomain
import com.digitalasset.canton.store.db.{DbTest, H2Test, PostgresTest}
import com.digitalasset.canton.BaseTest
import io.functionmeta.functionFullName
import org.scalatest.wordspec.AsyncWordSpec

import scala.concurrent.Future

trait DbRequestJournalStoreTest extends AsyncWordSpec with BaseTest with RequestJournalStoreTest {
  this: DbTest =>

  val domainId = DomainId(UniqueIdentifier.tryFromProtoPrimitive("da::default"))

  override def cleanDb(storage: DbStorage): Future[Unit] = {
    import storage.api._
    storage.update(
      DBIO.seq(sqlu"truncate table journal_requests", sqlu"truncate table head_clean_counters"),
      functionFullName,
    )
  }

  "DbRequestJournalStore" should {
    behave like requestJournalStore(() =>
      new DbRequestJournalStore(
        IndexedDomain.tryCreate(domainId, 1),
        storage,
        PositiveNumeric.tryCreate(2),
        BatchAggregatorConfig.defaultsForTesting,
        BatchAggregatorConfig.defaultsForTesting,
        enableAdditionalConsistencyChecksInOracle = true,
        timeouts,
        loggerFactory,
      )
    )
  }
}

class RequestJournalStoreTestH2 extends DbRequestJournalStoreTest with H2Test

class RequestJournalStoreTestPostgres extends DbRequestJournalStoreTest with PostgresTest
