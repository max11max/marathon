package mesosphere.marathon
package storage.migration

import java.time.OffsetDateTime

import akka.{Done, NotUsed}
import akka.http.scaladsl.unmarshalling.Unmarshaller
import akka.stream.Materializer
import akka.stream.scaladsl.{Flow, Sink}
import com.typesafe.scalalogging.StrictLogging
import mesosphere.marathon.api.v2.json.Formats
import mesosphere.marathon.core.condition.Condition
import mesosphere.marathon.core.instance.{Goal, Instance, Reservation}
import mesosphere.marathon.core.instance.Instance.{AgentInfo, Id, InstanceState}
import mesosphere.marathon.core.storage.store.impl.zk.{ZkId, ZkSerialized}
import mesosphere.marathon.core.storage.store.{IdResolver, PersistenceStore}
import mesosphere.marathon.core.task.Task
import mesosphere.marathon.core.task.state.NetworkInfo
import mesosphere.marathon.raml
import mesosphere.marathon.raml.Raml
import mesosphere.marathon.state.{Timestamp, UnreachableStrategy}
import mesosphere.marathon.storage.repository.InstanceRepository
import play.api.libs.json._
import play.api.libs.functional.syntax._
import play.api.libs.json.Reads._
import org.apache.mesos.{Protos => MesosProtos}

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.{ExecutionContext, Future}

class MigrationTo18(instanceRepository: InstanceRepository, persistenceStore: PersistenceStore[_, _, _]) extends MigrationStep with StrictLogging {

  override def migrate()(implicit ctx: ExecutionContext, mat: Materializer): Future[Done] = {
    MigrationTo18.migrateInstanceConditions(instanceRepository, persistenceStore)
  }
}

object MigrationTo18 extends MaybeStore with StrictLogging {

  import Instance.agentFormat
  import mesosphere.marathon.api.v2.json.Formats.TimestampFormat

  sealed trait ModificationStatus
  case object Modified extends ModificationStatus
  case object NotModified extends ModificationStatus

  case class ParsedValue[+A](value: A, status: ModificationStatus) {
    def map[B](f: A => B): ParsedValue[B] = ParsedValue(f(value), status)
    def isModified: Boolean = status == Modified
  }

  val migrationConditionReader = new Reads[ParsedValue[Condition]] {
    private def readString(j: JsReadable): JsResult[ParsedValue[Condition]] = j.validate[String].map {

      case created if created.toLowerCase == "created" => ParsedValue(Condition.Provisioned, Modified)
      case other => ParsedValue(Condition(other), NotModified)
    }
    override def reads(json: JsValue): JsResult[ParsedValue[Condition]] =
      readString(json).orElse {
        json.validate[JsObject].flatMap { obj => readString(obj \ "str") }
      }
  }

  /**
    * Read format for instance state where we replace created condition to provisioned.
    */
  val instanceStateReads17: Reads[ParsedValue[InstanceState]] = {
    (
      (__ \ "condition").read[ParsedValue[Condition]](migrationConditionReader) ~
      (__ \ "since").read[Timestamp] ~
      (__ \ "activeSince").readNullable[Timestamp] ~
      (__ \ "healthy").readNullable[Boolean] ~
      (__ \ "goal").read[Goal]
    ) { (condition, since, activeSince, healthy, goal) =>

        condition.map { c =>
          InstanceState(c, since, activeSince, healthy, goal)
        }
      }
  }

  val taskStatusReads17: Reads[ParsedValue[Task.Status]] = {
    (
      (__ \ "stagedAt").read[Timestamp] ~
      (__ \ "startedAt").readNullable[Timestamp] ~
      (__ \ "mesosStatus").readNullable[MesosProtos.TaskStatus](Task.Status.MesosTaskStatusFormat) ~
      (__ \ "condition").read[ParsedValue[Condition]](migrationConditionReader) ~
      (__ \ "networkInfo").read[NetworkInfo](Formats.TaskStatusNetworkInfoFormat)

    ) { (stagedAt, startedAt, mesosStatus, condition, networkInfo) =>
        condition.map { c =>
          Task.Status(stagedAt, startedAt, mesosStatus, c, networkInfo)
        }

      }
  }

  val taskReads17: Reads[ParsedValue[Task]] = {
    (
      (__ \ "taskId").read[Task.Id] ~
      (__ \ "runSpecVersion").read[Timestamp] ~
      (__ \ "status").read[ParsedValue[Task.Status]](taskStatusReads17)
    ) { (taskId, runSpecVersion, status) =>
        status.map { s =>
          Task(taskId, runSpecVersion, s)
        }
      }
  }

  val taskMapReads17: Reads[ParsedValue[Map[Task.Id, Task]]] = {

    mapReads(taskReads17).map {
      _.map { case (k, v) => Task.Id(k) -> v }
    }
      .map { taskMap =>
        if (taskMap.values.exists(_.isModified)) {
          ParsedValue(taskMap.mapValues(_.value), Modified)
        } else {
          ParsedValue(taskMap.mapValues(_.value), NotModified)
        }
      }
  }

  /**
    * Read format for old instance without goal.
    */
  val instanceJsonReads17: Reads[ParsedValue[Instance]] = {
    (
      (__ \ "instanceId").read[Instance.Id] ~
      (__ \ "agentInfo").read[AgentInfo] ~
      (__ \ "tasksMap").read[ParsedValue[Map[Task.Id, Task]]](taskMapReads17) ~
      (__ \ "runSpecVersion").read[Timestamp] ~
      (__ \ "state").read[ParsedValue[InstanceState]](instanceStateReads17) ~
      (__ \ "unreachableStrategy").readNullable[raml.UnreachableStrategy] ~
      (__ \ "reservation").readNullable[Reservation]
    ) { (instanceId, agentInfo, tasksMap, runSpecVersion, state, ramlUnreachableStrategy, reservation) =>
        val unreachableStrategy = ramlUnreachableStrategy.
          map(Raml.fromRaml(_)).getOrElse(UnreachableStrategy.default())

        if (List(state, tasksMap).exists(_.isModified)) {
          val instance = new Instance(instanceId, Some(agentInfo), state.value, tasksMap.value, runSpecVersion, unreachableStrategy, reservation)
          ParsedValue(instance, Modified)
        } else {
          val instance = new Instance(instanceId, Some(agentInfo), state.value, tasksMap.value, runSpecVersion, unreachableStrategy, reservation)
          ParsedValue(instance, NotModified)
        }

      }
  }

  implicit val instanceResolver: IdResolver[Instance.Id, JsValue, String, ZkId] =
    new IdResolver[Instance.Id, JsValue, String, ZkId] {
      override def toStorageId(id: Id, version: Option[OffsetDateTime]): ZkId =
        ZkId(category, id.idString, version)

      override val category: String = "instance"

      override def fromStorageId(key: ZkId): Id = Instance.Id.fromIdString(key.id)

      override val hasVersions: Boolean = false

      override def version(v: JsValue): OffsetDateTime = OffsetDateTime.MIN
    }

  implicit val instanceJsonUnmarshaller: Unmarshaller[ZkSerialized, JsValue] =
    Unmarshaller.strict {
      case ZkSerialized(byteString) =>
        Json.parse(byteString.utf8String)
    }

  /**
    * This function traverses all instances in ZK and sets the instance goal field.
    */
  def migrateInstanceConditions(instanceRepository: InstanceRepository, persistenceStore: PersistenceStore[_, _, _])(implicit mat: Materializer): Future[Done] = {

    logger.info("Starting instance condition migration")

    val countingSink: Sink[Done, NotUsed] = Sink.fold[Int, Done](0) { case (count, Done) => count + 1 }
      .mapMaterializedValue { f =>
        f.map(i => logger.info(s"$i instances migrated"))
        NotUsed
      }

    val filterNotModified = Flow[ParsedValue[Instance]]
      .mapConcat {
        case ParsedValue(instance, Modified) =>
          logger.info(s"${instance.instanceId} had `Created` condition, migration necessary")
          List(instance)
        case ParsedValue(instance, NotModified) =>
          logger.info(s"${instance.instanceId} doesn't need to be migrated")
          Nil
      }

    maybeStore(persistenceStore).map { store =>
      instanceRepository
        .ids()
        .mapAsync(1) { instanceId =>
          store.get[Instance.Id, JsValue](instanceId)
        }
        .via(parsingFlow)
        .via(filterNotModified)
        .mapAsync(1) { updatedInstance =>
          logger.info(s"Saving updated: $updatedInstance")
          instanceRepository.store(updatedInstance)
        }
        .alsoTo(countingSink)
        .runWith(Sink.ignore)
    } getOrElse {
      Future.successful(Done)
    }
  }

  /**
    * Extract instance from old format with replaced condition.
    * @param jsValue The instance as JSON.
    * @return The parsed instance.
    */
  def extractInstanceFromJson(jsValue: JsValue): ParsedValue[Instance] = jsValue.as[ParsedValue[Instance]](instanceJsonReads17)

  val parsingFlow = Flow[Option[JsValue]]
    .mapConcat {
      case Some(jsValue) =>
        extractInstanceFromJson(jsValue) :: Nil
      case None =>
        Nil
    }
}
