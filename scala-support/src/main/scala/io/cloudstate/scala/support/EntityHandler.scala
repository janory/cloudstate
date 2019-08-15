package io.cloudstate.scala.support

import akka.http.scaladsl.Http
import com.google.protobuf.ByteString
import scalapb.{GeneratedMessage, Message}
import com.google.protobuf.any.{Any => PbAny}
import io.cloudstate.eventsourced.EventSourcedSnapshot
import akka.util.{ByteString => AkkaBString}

import scala.concurrent.Future


// TODO create a real contenxt
class Context

trait EntityHandler { self =>

  type STATE <: GeneratedMessage with Message[STATE]

  protected var localState = Option.empty[STATE]

  def serializerState(state: STATE): PbAny

  def deSerializerState(state: AkkaBString): STATE

  def deserializePayload(typeUrl: String, payload: AkkaBString): GeneratedMessage

  def handleCommand(commandName: String, command: GeneratedMessage, ctx: Context): Option[STATE]

  def handleEvent(eventName: String, event: GeneratedMessage)

  protected def updateLocalState(newState: STATE): Unit = localState = Some(newState)

  protected def setInitial(entityId: String): STATE

  final def setOrInitLocalState(snapshot: Option[EventSourcedSnapshot], entityId: String): Unit = {
    val maybeSnapshotBs: Option[(String, ByteString)] = snapshot.flatMap(esSnapshot => esSnapshot.snapshot.flatMap(PbAny.unapply))
    localState = Some(maybeSnapshotBs match {
      case Some((_: String, snapshotBs: ByteString)) => deSerializerState(AkkaBString(snapshotBs.toByteArray))
      case None => setInitial(entityId)
    })
  }

  final def start(serviceName: String, persistenceId: String, snapshotEvery: Int): Future[Http.ServerBinding] = {
    new CloudState(serviceName, persistenceId, snapshotEvery, self).run()
  }
}
