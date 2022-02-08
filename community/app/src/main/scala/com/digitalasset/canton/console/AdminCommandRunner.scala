// Copyright (c) 2022 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.digitalasset.canton.console

import com.daml.ledger.configuration.LedgerId
import com.digitalasset.canton.admin.api.client.commands.{GrpcAdminCommand, HttpAdminCommand}
import com.digitalasset.canton.concurrent.Threading
import com.digitalasset.canton.config.{CantonConfig, TimeoutDuration}
import com.digitalasset.canton.console.CommandErrors.ConsoleTimeout
import com.digitalasset.canton.crypto.Crypto
import com.digitalasset.canton.environment.{CantonNode, CantonNodeBootstrap}
import com.digitalasset.canton.logging.{NamedLogging, TracedLogger}
import com.digitalasset.canton.tracing.NoTracing

import scala.annotation.tailrec

/** Support for running an admin command
  */
trait AdminCommandRunner {

  /** Run an admin command and return its result.
    * Depending on the admin client config details, it will either run the GRPC or the HTTP admin command.
    */
  protected[console] def adminCommand[Result](
      grpcCommand: GrpcAdminCommand[_, _, Result],
      httpCommand: HttpAdminCommand[_, _, Result],
  ): ConsoleCommandResult[Result]

  /** Run a GRPC admin command and return its result.
    * Most of the commands are only defined for the GRPC interface, so we default to showing an error message
    * if the command is called for a node configured with an HTTP interface.
    */
  protected[console] def adminCommand[Result](
      command: GrpcAdminCommand[_, _, Result]
  ): ConsoleCommandResult[Result] =
    adminCommand(command, new HttpAdminCommand.NotSupported[Result](command.fullName))

  protected[console] def tracedLogger: TracedLogger

}

object AdminCommandRunner {
  def retryUntilTrue(timeout: TimeoutDuration)(
      condition: => Boolean
  ): ConsoleCommandResult[Unit] = {
    val deadline = timeout.asFiniteApproximation.fromNow
    @tailrec
    def go(): ConsoleCommandResult[Unit] = {
      val res = condition
      if (!res) {
        if (deadline.hasTimeLeft()) {
          Threading.sleep(100)
          go()
        } else {
          ConsoleTimeout.Error(timeout.asJavaApproximation)
        }
      } else {
        CommandSuccessful(())
      }
    }
    go()
  }
}

/** Support for running an ledgerApi commands
  */
trait LedgerApiCommandRunner {

  /** Run an admin command and return its result.
    */
  protected[console] def ledgerApiCommand[Result](
      commandGenerator: LedgerId => GrpcAdminCommand[_, _, Result]
  ): ConsoleCommandResult[Result]

  protected[console] def ledgerApiCommand[Result](
      command: GrpcAdminCommand[_, _, Result]
  ): ConsoleCommandResult[Result]

}

/** Support for inspecting the instance */
trait BaseInspection[I <: CantonNode] {

  def underlying: Option[I] = {
    runningNode.flatMap(_.getNode)
  }

  protected[console] def runningNode: Option[CantonNodeBootstrap[I]]
  protected[console] def name: String

  protected[console] def access[T](ops: I => T): T = {
    ops(
      runningNode
        .getOrElse(throw new IllegalArgumentException(s"instance $name is not running"))
        .getNode
        .getOrElse(
          throw new IllegalArgumentException(
            s"instance $name is still starting or awaiting manual initialisation."
          )
        )
    )
  }

  protected def crypto: Crypto = {
    runningNode
      .getOrElse(throw new IllegalArgumentException(s"instance $name is not running."))
      .crypto
  }

}

trait FeatureFlagFilter extends NamedLogging with NoTracing {

  protected def consoleEnvironment: ConsoleEnvironment

  protected def cantonConfig: CantonConfig = consoleEnvironment.environment.config

  private def checkEnabled[T](flag: Boolean, config: String, command: => T): T =
    if (flag) {
      command
    } else {
      logger.error(
        s"The command is currently disabled. You need to enable it explicitly by setting `canton.features.${config} = yes` in your Canton configuration file (`.conf`)"
      )
      throw new CommandFailure()
    }

  protected def check[T](flag: FeatureFlag)(command: => T): T =
    checkEnabled(consoleEnvironment.featureSet.contains(flag), flag.configName, command)

}
