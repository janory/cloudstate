package io.cloudstate.scala.support

import akka.NotUsed
import akka.stream.Materializer
import akka.stream.scaladsl.{Sink, Source}
import io.cloudstate.eventsourced.EventSourcedStreamOut.Message
import io.cloudstate.eventsourced.EventSourcedStreamOut.Message.{Reply => MessageReply}
import io.cloudstate.eventsourced._
import io.cloudstate.eventsourced.EventSourcedReply
import io.cloudstate.entity.{Reply => EntityReply, ClientAction, Command, Failure => EntityFailure}
import com.google.protobuf.any.{Any => PbAny}
import io.cloudstate.eventsourced.EventSourcedStreamOut.Message.{Failure => FailureWrapper}
import akka.util.{ByteString => AkkaBString}
import io.cloudstate.entity.ClientAction.Action

import scala.language.postfixOps


class EventSourcedServiceImpl(entityHandler: EntityHandler)(implicit mat: Materializer) extends EventSourced {

  import EventSourcedServiceImpl._

  override def handle(in: Source[EventSourcedStreamIn, NotUsed]): Source[EventSourcedStreamOut, NotUsed] = {
    var maybeEntityHandler = Option.empty[EntityHandler]

    in.to(Sink.onComplete(done => {
      println("Stream completed")
    }))

    in.map {
      case EventSourcedStreamIn(msg) if msg.isInit => msg.init.get
      case EventSourcedStreamIn(msg) if msg.isEvent => msg.event.get
      case EventSourcedStreamIn(msg) if msg.isCommand => msg.command.get
    }.map {
      case init: EventSourcedInit =>
        maybeEntityHandler match {
          case Some(_) =>
            println("Terminating entity due to duplicate init message.")
            Failure("Init message received twice.")
          case None =>
            entityHandler.setOrInitLocalState(init.snapshot, init.entityId)
            maybeEntityHandler = Some(entityHandler)
            Message.Empty
        }
      case event: EventSourcedEvent =>
        maybeEntityHandler match {
          case Some(handler) =>
            val (typeUrl, payload) = PbAny.unapply(event.payload.get).get
            val deserializedEvent = handler.deserializePayload(typeUrl, AkkaBString(payload.toByteArray))
            handler.handleEvent(typeUrl, deserializedEvent)
            Message.Empty
          case None =>
            Failure("Event received before init")
        }
      case command: Command =>
        maybeEntityHandler match {
          case Some(handler) =>
            val (typeUrl, payload) = PbAny.unapply(command.payload.get).get
            val deserializedCommand = handler.deserializePayload(typeUrl, AkkaBString(payload.toByteArray))
            val newState = handler.handleCommand(command.name, deserializedCommand, new Context())
            val maybeSerializedState: Option[PbAny] = newState.map(handler.serializerState)
            maybeSerializedState match {
              case Some(serializedState) => Reply(command.id, serializedState)
              case None => Message.Empty
            }
          case None =>
            Failure("Command received before init")
        }
      case _ => Failure("Unknown message received")
    }
      .filter(!_.isEmpty)
      .takeWhile(!_.isFailure, inclusive = true)
      .map(EventSourcedStreamOut.apply)
  }
}

object EventSourcedServiceImpl {
   object Failure {
     def apply(reason: String): Message = FailureWrapper(EntityFailure(description = reason))
   }

  object Reply {
    def apply(commandId: Long, message: PbAny) = MessageReply(
      EventSourcedReply(commandId, Some(
        ClientAction(Action.Reply(EntityReply(Some(message))))
      ))
    )
  }
}


