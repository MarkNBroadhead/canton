// Copyright (c) 2022 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.digitalasset.canton.participant.store.db

import com.digitalasset.canton.BaseTest
import com.digitalasset.canton.participant.store.CommandDeduplicationStoreTest
import com.digitalasset.canton.resource.DbStorage
import com.digitalasset.canton.store.db.{DbTest, H2Test, PostgresTest}
import io.functionmeta.functionFullName
import org.scalatest.wordspec.AsyncWordSpec

import scala.concurrent.Future

trait DbCommandDeduplicationStoreTest
    extends AsyncWordSpec
    with BaseTest
    with CommandDeduplicationStoreTest {
  this: DbTest =>
  override def cleanDb(storage: DbStorage): Future[Unit] = {
    import storage.api._
    storage.update(
      DBIO.seq(
        sqlu"truncate table command_deduplication",
        sqlu"truncate table command_deduplication_pruning",
      ),
      functionFullName,
    )
  }

  "DbCommandDeduplicationStore" should {
    behave like commandDeduplicationStore(() =>
      new DbCommandDeduplicationStore(
        storage,
        timeouts,
        loggerFactory,
      )
    )
  }
}

class CommandDeduplicationStoreTestH2 extends DbCommandDeduplicationStoreTest with H2Test

class CommandDeduplicationStoreTestPostgres
    extends DbCommandDeduplicationStoreTest
    with PostgresTest
