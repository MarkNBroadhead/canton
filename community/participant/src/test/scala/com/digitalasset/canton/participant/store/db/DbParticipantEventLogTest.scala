// Copyright (c) 2022 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.digitalasset.canton.participant.store.db

import com.digitalasset.canton.participant.store.ParticipantEventLogTest
import com.digitalasset.canton.resource.DbStorage
import com.digitalasset.canton.store.db.{DbTest, H2Test, PostgresTest}
import com.digitalasset.canton.store.memory.InMemoryIndexedStringStore

import scala.concurrent.Future

trait DbParticipantEventLogTest extends ParticipantEventLogTest { this: DbTest =>

  override def cleanDb(storage: DbStorage): Future[Unit] = {
    import storage.api._
    storage.update_(
      sqlu"delete from event_log where log_id = $id", // table shared with other tests
      operationName = s"${this.getClass}: clean db",
    )
  }
  override def newStore: DbParticipantEventLog =
    new DbParticipantEventLog(id, storage, InMemoryIndexedStringStore(), timeouts, loggerFactory)
}

class ParticipantEventLogTestH2 extends DbParticipantEventLogTest with H2Test

class ParticipantEventLogTestPostgres extends DbParticipantEventLogTest with PostgresTest
