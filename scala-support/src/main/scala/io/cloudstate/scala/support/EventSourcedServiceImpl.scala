package io.cloudstate.scala.support

import java.lang.reflect.Method

import akka.NotUsed
import akka.stream.Materializer
import akka.stream.scaladsl.{Sink, Source}
import akka.util.ByteString
import com.example.shoppingcart.{Cart, GetShoppingCart, ShoppingCart}
import io.cloudstate.entity.{Command, Failure}
import io.cloudstate.eventsourced.EventSourcedStreamOut.Message
import io.cloudstate.eventsourced.EventSourcedStreamOut.Message.{Failure => FailureWrapper}
import io.cloudstate.eventsourced._

class EventSourcedServiceImpl(implicit mat: Materializer) extends EventSourced {


  override def handle(in: Source[EventSourcedStreamIn, NotUsed]): Source[EventSourcedStreamOut, NotUsed] = {
    var maybeEventHandler = Option.empty[EventHandler]
    var maybeCommandHandler = Option.empty[CommandHandler]

    in.to(Sink.onComplete(done => {
      println("Stream completed")
    }))

    in.map((esStream: EventSourcedStreamIn) => {
      if (esStream.message.isInit) {
        val serviceName = esStream.message.init.get.serviceName
        EventSourcedStreamOut((maybeEventHandler, maybeCommandHandler) match {
          case (Some(_), Some(_)) | (Some(_), None) | (None, Some(_)) => FailureWrapper(Failure(description = "Init message received twice."))
          case _ => {
            println(s"New EventHandler & CommandHandler has been registered for service $serviceName")
            maybeEventHandler = Some(new EventHandler())
            maybeCommandHandler = Some(new CommandHandler())
            Message.Empty
          }
        })
      } else if (esStream.message.isEvent) {
        val event: EventSourcedEvent = esStream.message.event.get
        EventSourcedStreamOut(maybeEventHandler match {
          case Some(eventHandler) => eventHandler.handleEvent(event)
          case None => FailureWrapper(Failure(description = "Unknown message received before init"))
        })
      } else {
        val command: Command = esStream.message.command.get
        EventSourcedStreamOut(maybeCommandHandler match {
          case Some(eventHandler) => eventHandler.handleCommand(command)
          case None => FailureWrapper(Failure(description = "Unknown message received before init"))
        })
      }
    })
  }
}


class EventHandler {
  def handleEvent(event: EventSourcedEvent) = {
    println(s"Received event ${event.sequence} with type ${event.payload.get.typeUrl}")
    Message.Empty
  }
}

class CommandHandler() {
//  var snapshot: google.protobuf.Any = _

  def handleCommand(command: Command) = {
    println(s"Received command ${command.name} with type ${command.payload.get.typeUrl}")

    if(command.name == "GetCart") {
      val bs: ByteString = ByteString(command.payload.get.value.toByteArray)
      val deserializedCommand: GetShoppingCart = ShoppingCart.Serializers.GetShoppingCartSerializer.deserialize(bs)
//      val deserializedSnapshot = deserialize(snapshot)
//      getCart(deserializedCommand, deserializedSnapshot)
      Message.Empty
    } else {
      FailureWrapper(Failure(
        commandId = command.id,
        description = s"Unknown command named ${command.name}"))
    }
  }

  def getCart(request: GetShoppingCart, cart: Any) = cart
}