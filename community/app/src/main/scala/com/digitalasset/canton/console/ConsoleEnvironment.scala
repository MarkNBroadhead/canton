// Copyright (c) 2022 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.digitalasset.canton.console

import ammonite.util.Bind
import com.digitalasset.canton.DomainAlias
import com.digitalasset.canton.admin.api.client.data.CantonStatus
import com.digitalasset.canton.config.RequireTypes.{InstanceName, NonNegativeInt}
import com.digitalasset.canton.config.{ConsoleCommandTimeout, ProcessingTimeout, TimeoutDuration}
import com.digitalasset.canton.console.CommandErrors.{
  CantonCommandError,
  CommandInternalError,
  GenericCommandError,
}
import com.digitalasset.canton.console.Help.{Description, Summary, Topic}
import com.digitalasset.canton.crypto.Fingerprint
import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.environment.Environment
import com.digitalasset.canton.lifecycle.{FlagCloseable, Lifecycle}
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.sequencing.{GrpcSequencerConnection, SequencerConnection}
import com.digitalasset.canton.time.{NonNegativeFiniteDuration, SimClock}
import com.digitalasset.canton.topology.{Identifier, ParticipantId}
import com.digitalasset.canton.tracing.{NoTracing, TraceContext, TracerProvider}
import io.opentelemetry.api.trace.Tracer

import java.time.{Duration, Instant}
import java.util.concurrent.atomic.AtomicReference
import scala.concurrent.duration
import scala.concurrent.duration.FiniteDuration
import scala.reflect.runtime.{universe => ru}
import scala.util.control.NonFatal

case class NodeReferences[A, R <: A, L <: A](local: Seq[L], remote: Seq[R]) {
  val all: Seq[A] = local ++ remote
}

/** The environment in which console commands are evaluated.
  */
@SuppressWarnings(Array("org.wartremover.warts.Any")) // required for `Binding[_]` usage
trait ConsoleEnvironment extends NamedLogging with FlagCloseable with NoTracing {
  type Env <: Environment
  type DomainLocalRef <: LocalDomainReference
  type DomainRemoteRef <: RemoteDomainReference
  type Status <: CantonStatus

  def health: CantonHealthAdministration[Status]

  /** the underlying Canton runtime environment */
  val environment: Env

  /** determines the control exception thrown on errors */
  val errorHandler: ConsoleErrorHandler = ThrowErrorHandler

  /** the console for user facing output */
  val consoleOutput: ConsoleOutput

  private val tracerProvider =
    TracerProvider.Factory(environment.config.monitoring.tracing.tracer, "console")
  private[console] val tracer: Tracer = tracerProvider.tracer

  /** Definition of the startup order of local instances.
    * Nodes support starting up in any order however to avoid delays/warnings we opt to start in the most desirable order
    * for simple execution. (e.g. domains started before participants).
    * Implementations should just return a int for the instance (typically just a static value based on type),
    * and then the console will start these instances for lower to higher values.
    */
  protected def startupOrderPrecedence(instance: LocalInstanceReference): Int

  /** The order that local nodes would ideally be started in. */
  final val startupOrdering: Ordering[LocalInstanceReference] =
    (x: LocalInstanceReference, y: LocalInstanceReference) =>
      startupOrderPrecedence(x) compare startupOrderPrecedence(y)

  /** allows for injecting a custom admin command runner during tests */
  protected def createAdminCommandRunner: ConsoleEnvironment => ConsoleGrpcAdminCommandRunner

  protected override val loggerFactory: NamedLoggerFactory = environment.loggerFactory

  private val commandTimeoutReference: AtomicReference[ConsoleCommandTimeout] =
    new AtomicReference[ConsoleCommandTimeout](environment.config.parameters.timeouts.console)

  private val featureSetReference: AtomicReference[HelperItems] =
    new AtomicReference[HelperItems](HelperItems(environment.config.features.featureFlags))

  /** Generate implementation specific help items for local domains */
  protected def localDomainHelpItems(
      scope: Set[FeatureFlag],
      localDomain: DomainLocalRef,
  ): Seq[Help.Item]

  /** Generate implementation specific help items for remote domains */
  protected def remoteDomainHelpItems(
      scope: Set[FeatureFlag],
      remoteDomain: DomainRemoteRef,
  ): Seq[Help.Item]

  private case class HelperItems(scope: Set[FeatureFlag]) {
    lazy val participantHelperItems = {
      // due to the use of reflection to grab the help-items, i need to write the following, repetitive stuff explicitly
      val subItems =
        if (participants.local.nonEmpty)
          participants.local.headOption.toList.flatMap(p =>
            Help.getItems(p, baseTopic = Seq("$participant"), scope = scope)
          )
        else if (participants.remote.nonEmpty)
          participants.remote.headOption.toList.flatMap(p =>
            Help.getItems(p, baseTopic = Seq("$participant"), scope = scope)
          )
        else Seq()
      Help.Item("$participant", None, Summary(""), Description(""), Topic(Seq()), subItems)
    }

    lazy val domainHelperItems = {
      val subItems =
        if (domains.local.nonEmpty)
          domains.local.headOption.toList.flatMap(localDomainHelpItems(scope, _))
        else if (domains.remote.nonEmpty)
          domains.remote.headOption.toList.flatMap(remoteDomainHelpItems(scope, _))
        else Seq()
      Help.Item("$domain", None, Summary(""), Description(""), Topic(Seq()), subItems)
    }

    lazy val filteredHelpItems = {
      helpItems.filter(x => scope.contains(x.summary.flag))
    }

    lazy val all = filteredHelpItems :+ participantHelperItems :+ domainHelperItems

  }

  protected def timeouts: ProcessingTimeout = environment.config.parameters.timeouts.processing

  /** @return maximum runtime of a console command
    */
  def commandTimeouts: ConsoleCommandTimeout = commandTimeoutReference.get()

  def setCommandTimeout(newTimeout: TimeoutDuration): Unit = {
    require(newTimeout.duration > duration.Duration.Zero, "The command timeout must be positive!")
    val _ = commandTimeoutReference.updateAndGet(cur => cur.copy(bounded = newTimeout))
  }

  /** returns the currently enabled feature sets */
  def featureSet: Set[FeatureFlag] = featureSetReference.get().scope

  def updateFeatureSet(flag: FeatureFlag, include: Boolean): Unit = {
    val _ = featureSetReference.updateAndGet { x =>
      val scope = if (include) x.scope + flag else x.scope - flag
      HelperItems(scope)
    }
  }

  /** Holder for top level values including their name, their value, and a description to display when `help` is printed.
    */
  protected case class TopLevelValue[T](
      nameUnsafe: String,
      summary: String,
      value: T,
      topic: Seq[String] = Seq(),
  )(implicit tag: ru.TypeTag[T]) {

    /** The name is surrounded with back-ticks to enforce valid scala identifier.
      * @throws com.digitalasset.canton.config.RequireTypes$.InstanceName$.InvalidInstanceName
      *   if `nameUnsafe` is not a valid instance name.
      *   It is up to the caller to fail more gracefully.
      */
    lazy val asBind: Bind[T] = {
      InstanceName.tryCreate(nameUnsafe)

      // Surround with back-ticks to handle the case that name is a reserved keyword in scala.
      Bind("`" + nameUnsafe + "`", value)
    }
    lazy val asHelpItem: Help.Item =
      Help.Item(nameUnsafe, None, Help.Summary(summary), Help.Description(""), Help.Topic(topic))
  }

  object TopLevelValue {

    /** Provide all details but the value itself. A subsequent call can then specify the value from another location.
      * This oddness is to allow the ConsoleEnvironment implementations to specify the values of node instances they
      * use as scala's runtime reflection can't easily take advantage of the type members we have available here.
      */
    case class Partial(name: String, summary: String, topics: Seq[String] = Seq.empty) {
      def apply[T](value: T)(implicit t: ru.TypeTag[T]): TopLevelValue[T] =
        TopLevelValue(name, summary, value, topics)
    }
  }

  // lazy to prevent publication of this before this has been fully initialized
  lazy val grpcAdminCommandRunner: ConsoleGrpcAdminCommandRunner = createAdminCommandRunner(this)

  /** Run a console command.
    */
  @SuppressWarnings(Array("org.wartremover.warts.Null"))
  def run[A](result: => ConsoleCommandResult[A]): A = {
    val resultValue: ConsoleCommandResult[A] =
      try {
        result
      } catch {
        case err: Throwable =>
          CommandInternalError.ErrorWithException(err).logWithContext()
          err match {
            case NonFatal(_) =>
              // No need to rethrow err, as it has been logged and output
              errorHandler.handleInternalError()
            case _ =>
              // Rethrow err, as it is a bad practice to discard fatal errors.
              // As a result, the error may be printed several times,
              // but there is no guarantee that the log is still working.
              // So it is better to err on the safe side.
              throw err
          }
      }

    def invocationContext(): Map[String, String] =
      findInvocationSite() match {
        case Some((funcName, callSite)) => Map("function" -> funcName, "callsite" -> callSite)
        case None => Map()
      }

    resultValue match {
      case null =>
        CommandInternalError.NullError().logWithContext(invocationContext())
        errorHandler.handleInternalError()
      case CommandSuccessful(value) =>
        value
      case err: CantonCommandError =>
        err.logWithContext(invocationContext())
        errorHandler.handleCommandFailure()
      case err: GenericCommandError =>
        val errMsg = findInvocationSite() match {
          case Some((funcName, site)) =>
            err.cause + s"\n  Command ${funcName} invoked from ${site}"
          case None => err.cause
        }
        logger.error(errMsg)
        errorHandler.handleCommandFailure()
    }
  }

  private def findInvocationSite(): Option[(String, String)] = {
    val stack = Thread.currentThread().getStackTrace
    // assumption: first few stack elements are all in our set of known packages. our call-site is
    // the first entry outside of our package
    // also skip all scala packages because a collection's map operation is not an informative call site
    val myPackages =
      Seq("com.digitalasset.canton.console", "com.digitalasset.canton.environment", "scala.")

    def isKnown(element: StackTraceElement): Boolean =
      myPackages.exists(element.getClassName.startsWith)

    stack.sliding(2).collectFirst {
      case Array(callee, caller) if isKnown(callee) && !isKnown(caller) =>
        val drop = callee.getClassName.lastIndexOf(".") + 1
        val funcName = callee.getClassName.drop(drop) + "." + callee.getMethodName
        (funcName, s"${caller.getFileName}:${caller.getLineNumber}")
    }

  }

  /** Print help for items in the top level scope.
    */
  def help(): Unit = {
    consoleOutput.info(Help.format(featureSetReference.get().filteredHelpItems: _*))
  }

  /** Print detailed help for a top-level item in the top level scope.
    */
  def help(cmd: String): Unit =
    consoleOutput.info(Help.forMethod(featureSetReference.get().all, cmd))

  def helpItems: Seq[Help.Item] =
    topLevelValues.map(_.asHelpItem) ++
      Help.fromObject(ConsoleMacros) ++
      Help.fromObject(this) :+
      (
        Help.Item(
          "help",
          None,
          Help.Summary(
            "Help with console commands; type help(\"<command>\") for detailed help for <command>"
          ),
          Help.Description(""),
          Help.Topic(Help.defaultTopLevelTopic),
        ),
      ) :+
      (Help.Item(
        "exit",
        None,
        Help.Summary("Leave the console"),
        Help.Description(""),
        Help.Topic(Help.defaultTopLevelTopic),
      ))

  lazy val participants: NodeReferences[
    ParticipantReference,
    RemoteParticipantReference,
    LocalParticipantReference,
  ] =
    NodeReferences(
      environment.config.participantsByString.keys.map(createParticipantReference).toSeq,
      environment.config.remoteParticipantsByString.keys
        .map(createRemoteParticipantReference)
        .toSeq,
    )
  lazy val domains: NodeReferences[DomainReference, DomainRemoteRef, DomainLocalRef] =
    NodeReferences(
      environment.config.domainsByString.keys.map(createDomainReference).toSeq,
      environment.config.remoteDomainsByString.keys.map(createRemoteDomainReference).toSeq,
    )

  // the scala compiler / wartremover gets confused here if I use ++ directly
  def mergeLocalInstances(locals: Seq[LocalInstanceReference]*): Seq[LocalInstanceReference] =
    locals.flatten
  def mergeRemoteInstances(remotes: Seq[InstanceReference]*): Seq[InstanceReference] =
    remotes.flatten

  lazy val nodes: NodeReferences[InstanceReference, InstanceReference, LocalInstanceReference] = {
    NodeReferences(
      mergeLocalInstances(participants.local, domains.local),
      mergeRemoteInstances(participants.remote, domains.remote),
    )
  }

  protected def helpText(typeName: String, name: String) =
    s"Manage $typeName '${name}'; type '${name} help' or '${name} help" + "(\"<methodName>\")' for more help"

  protected val topicNodeReferences = "Node References"
  protected val topicGenericNodeReferences = "Generic Node References"
  protected val genericNodeReferencesDoc = " (.all, .local, .remote)"

  protected def domainsTopLevelValue(
      h: TopLevelValue.Partial,
      domains: NodeReferences[DomainReference, DomainRemoteRef, DomainLocalRef],
  ): TopLevelValue[NodeReferences[DomainReference, DomainRemoteRef, DomainLocalRef]]

  /** Supply the local domain value used by the implementation */
  protected def localDomainTopLevelValue(
      h: TopLevelValue.Partial,
      d: DomainLocalRef,
  ): TopLevelValue[DomainLocalRef]

  /** Supply the remote domain value used by the implementation */
  protected def remoteDomainTopLevelValue(
      h: TopLevelValue.Partial,
      d: DomainRemoteRef,
  ): TopLevelValue[DomainRemoteRef]

  /** Assemble top level values with their identifier name, value binding, and help description.
    */
  protected def topLevelValues: Seq[TopLevelValue[_]] = {
    val nodeTopic = Seq(topicNodeReferences)
    val localParticipantBinds: Seq[TopLevelValue[_]] =
      participants.local.map(p =>
        TopLevelValue(p.name, helpText("participant", p.name), p, nodeTopic)
      )
    val remoteParticipantBinds: Seq[TopLevelValue[_]] =
      participants.remote.map(p =>
        TopLevelValue(p.name, helpText("remote participant", p.name), p, nodeTopic)
      )
    val localDomainBinds: Seq[TopLevelValue[_]] =
      domains.local.map(d =>
        localDomainTopLevelValue(
          TopLevelValue.Partial(d.name, helpText("local domain", d.name), nodeTopic),
          d,
        )
      )
    val remoteDomainBinds: Seq[TopLevelValue[_]] =
      domains.remote.map(d =>
        remoteDomainTopLevelValue(
          TopLevelValue.Partial(d.name, helpText("remote domain", d.name), nodeTopic),
          d,
        )
      )
    val clockBinds: Option[TopLevelValue[_]] =
      environment.simClock.map(cl =>
        TopLevelValue("clock", "Simulated time", new SimClockCommand(cl))
      )
    val referencesTopic = Seq(topicGenericNodeReferences)
    localParticipantBinds ++ remoteParticipantBinds ++
      localDomainBinds ++ remoteDomainBinds ++ clockBinds.toList :+
      TopLevelValue(
        "participants",
        "All participant nodes" + genericNodeReferencesDoc,
        participants,
        referencesTopic,
      ) :+
      domainsTopLevelValue(
        TopLevelValue
          .Partial("domains", "All domain nodes" + genericNodeReferencesDoc, referencesTopic),
        domains,
      ) :+
      TopLevelValue("nodes", "All nodes" + genericNodeReferencesDoc, nodes, referencesTopic)
  }

  /** Bindings for ammonite
    * @throws com.digitalasset.canton.config.RequireTypes$.InstanceName$.InvalidInstanceName
    *   if `nameUnsafe` is not a valid instance name.
    *   It is up to the caller to fail more gracefully.
    * @throws java.lang.IllegalStateException if names are not unique.
    */
  lazy val bindings: Seq[Bind[_]] = {
    val values = topLevelValues
    validateNames(values)
    val binds = topLevelValues.map(_.asBind) :+
      selfAlias() // secretly add a reference to this instance to resolve implicit references within the console within the console
    validateNameUniqueness(binds)

    binds
  }

  private def validateNames(
      values: Seq[TopLevelValue[_]]
  ): Unit = values.foreach(v => InstanceName.tryCreate(v.nameUnsafe))

  private def validateNameUniqueness(
      binds: Seq[Bind[_]]
  ): Unit = {
    val names = binds.map(_.name)
    val nonUniqueNames = {
      names.groupBy(identity).collect {
        case (name, occurrences) if occurrences.size > 1 =>
          s"$name (${occurrences.size} occurrences)"
      }
    }
    if (nonUniqueNames.nonEmpty) {
      throw new IllegalStateException(
        s"""Node names must be unique and must differ from reserved keywords. Please revisit node names in your config file.
           |Offending names: ${nonUniqueNames.mkString("(", ", ", ")")}""".stripMargin
      )
    }
  }

  private def createParticipantReference(name: String): LocalParticipantReference =
    new LocalParticipantReference(this, name)
  private def createRemoteParticipantReference(name: String): RemoteParticipantReference =
    new RemoteParticipantReference(this, name)

  protected def createDomainReference(name: String): DomainLocalRef
  protected def createRemoteDomainReference(name: String): DomainRemoteRef

  /** So we can we make this available
    */
  protected def selfAlias(): Bind[_] = Bind(ConsoleEnvironmentBinding.BindingName, this)

  override def onClosed(): Unit = {
    Lifecycle.close(grpcAdminCommandRunner, environment, tracerProvider)(logger)
  }

  def startAll(): Unit = {
    import ConsoleEnvironment.Implicits.toInstanceReferenceExtensions
    implicit val consoleEnvironment = this

    nodes.local.start()
  }

  def stopAll(): Unit = {
    import ConsoleEnvironment.Implicits.toInstanceReferenceExtensions
    implicit val consoleEnvironment = this

    nodes.local.stop()
  }

}

/** Expose a Canton [[environment.Environment]] in a way that's easy to deal with from a REPL.
  */
object ConsoleEnvironment {

  trait Implicits {

    import scala.language.implicitConversions

    implicit def toInstanceReferenceExtensions(
        instances: Seq[LocalInstanceReference]
    ): LocalInstancesExtensions =
      new LocalInstancesExtensions.Impl(instances)

    /** Extensions for many instance references
      */
    implicit def toLocalDomainExtensions(
        instances: Seq[LocalDomainReference]
    ): LocalInstancesExtensions =
      new LocalDomainReferencesExtensions(instances)

    /** Extensions for many participant references
      */
    implicit def toParticipantReferencesExtensions(participants: Seq[ParticipantReference])(implicit
        consoleEnvironment: ConsoleEnvironment
    ): ParticipantReferencesExtensions =
      new ParticipantReferencesExtensions(participants)

    implicit def toLocalParticipantReferencesExtensions(
        participants: Seq[LocalParticipantReference]
    )(implicit consoleEnvironment: ConsoleEnvironment): LocalParticipantReferencesExtensions =
      new LocalParticipantReferencesExtensions(participants)

    /** Implicitly map strings to DomainAlias, Fingerprint and Identifier
      */
    implicit def toDomainAlias(alias: String): DomainAlias = DomainAlias.tryCreate(alias)
    implicit def toDomainAliases(aliases: Seq[String]): Seq[DomainAlias] =
      aliases.map(DomainAlias.tryCreate)

    implicit def toInstanceName(name: String): InstanceName = InstanceName.tryCreate(name)

    implicit def toGrpcSequencerConnection(connection: String): SequencerConnection =
      GrpcSequencerConnection.tryCreate(connection)
    implicit def toGSequencerConnection(
        ref: InstanceReferenceWithSequencerConnection
    ): SequencerConnection =
      ref.sequencerConnection

    implicit def toIdentifier(id: String): Identifier = Identifier.tryCreate(id)
    implicit def toFingerprint(fp: String): Fingerprint = Fingerprint.tryCreate(fp)

    /** Implicitly map ParticipantReferences to the ParticipantId
      */
    implicit def toParticipantId(reference: ParticipantReference): ParticipantId = reference.id

    /** Implicitly map an `Int` to a `NonNegativeInt`.
      * @throws java.lang.IllegalArgumentException if `n` is negative
      */
    implicit def toNonNegativeInt(n: Int): NonNegativeInt = NonNegativeInt.tryCreate(n)

    /** Implicitly convert a duration to a timeout duration
      * @throws java.lang.IllegalArgumentException if `n` is negative duration
      */
    implicit val toTimeoutDuration: FiniteDuration => TimeoutDuration =
      TimeoutDuration.tryFromDuration(_)

    implicit def toNonNegativeFiniteDuration(timeoutDuration: TimeoutDuration) =
      timeoutDuration.duration match {
        case _: duration.Duration.Infinite =>
          throw new IllegalArgumentException("Expecting finite duration but Infinite found")

        case duration: FiniteDuration =>
          NonNegativeFiniteDuration(duration.asJavaApproximation)
      }

  }

  object Implicits extends Implicits

}

class SimClockCommand(clock: SimClock) {

  @Help.Description("Get current time")
  def now: Instant = clock.now.toInstant

  @Help.Description("Advance time to given time-point")
  def advanceTo(timestamp: Instant): Unit = TraceContext.withNewTraceContext {
    implicit traceContext =>
      clock.advanceTo(CantonTimestamp.assertFromInstant(timestamp))
  }

  @Help.Description("Advance time by given time-period")
  def advance(duration: Duration): Unit = TraceContext.withNewTraceContext {
    implicit traceContext =>
      clock.advance(duration)
  }

  @Help.Summary("Reset simulation clock")
  def reset(): Unit = clock.reset()

}
