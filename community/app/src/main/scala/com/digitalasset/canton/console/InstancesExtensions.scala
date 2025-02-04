// Copyright (c) 2022 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.digitalasset.canton.console

/** Aliases to manage a sequence of instances in a REPL environment
  */
trait LocalInstancesExtensions extends Helpful {

  import ConsoleCommandResult.runAll

  def instances: Seq[LocalInstanceReference]

  @Help.Summary("Database management related operations")
  @Help.Group("Database")
  object db extends Helpful {

    @Help.Summary("Migrate all databases")
    def migrate()(implicit consoleEnvironment: ConsoleEnvironment): Unit = {
      val _ = runAll(instances.sorted(consoleEnvironment.startupOrdering)) {
        _.migrateDbCommand()
      }
    }

    @Help.Summary("Only use when advised - repair the database migration of all nodes")
    @Help.Description(
      """In some rare cases, we change already applied database migration files in a new release and the repair
        |command resets the checksums we use to ensure that in general already applied migration files have not been changed.
        |You should only use `db.repair_migration` when advised and otherwise use it at your own risk - in the worst case running 
        |it may lead to data corruption when an incompatible database migration (one that should be rejected because 
        |the already applied database migration files have changed) is subsequently falsely applied.
        |"""
    )
    def repair_migration(
        force: Boolean = false
    )(implicit consoleEnvironment: ConsoleEnvironment): Unit = {
      val _ = runAll(instances.sorted(consoleEnvironment.startupOrdering)) {
        _.repairMigrationCommand(force)
      }
    }

  }

  @Help.Summary("Start all")
  def start()(implicit consoleEnvironment: ConsoleEnvironment): Unit = {
    val _ = runAll(instances.sorted(consoleEnvironment.startupOrdering)) {
      _.startCommand()
    }
  }
  @Help.Summary("Stop all")
  def stop()(implicit consoleEnvironment: ConsoleEnvironment): Unit = {
    val _ = runAll(instances.sorted(consoleEnvironment.startupOrdering.reverse)) { _.stopCommand() }
  }

}

object LocalInstancesExtensions {
  class Impl(val instances: Seq[LocalInstanceReference]) extends LocalInstancesExtensions {}
}

class LocalDomainReferencesExtensions(domains: Seq[LocalDomainReference])
    extends LocalInstancesExtensions {

  override def instances: Seq[LocalDomainReference] = domains

}
