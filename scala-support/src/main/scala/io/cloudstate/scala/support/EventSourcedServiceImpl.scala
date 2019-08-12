package io.cloudstate.scala.support

import akka.NotUsed
import akka.stream.Materializer
import akka.stream.scaladsl.{Sink, Source}
import akka.util.ByteString
import com.example.shoppingcart.{Cart, GetShoppingCart, ShoppingCart}
import com.google.protobuf
import com.google.protobuf.any.{Any => pbAny}
import io.cloudstate.entity.ClientAction.Action
import io.cloudstate.entity.{ClientAction, Command, Failure}
import io.cloudstate.eventsourced.EventSourcedStreamOut.Message
import io.cloudstate.eventsourced.EventSourcedStreamOut.Message.{Reply, Failure => FailureWrapper}
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
        val snapshot: Option[EventSourcedSnapshot] = esStream.message.init.get.snapshot

        EventSourcedStreamOut((maybeEventHandler, maybeCommandHandler) match {
          case (Some(_), Some(_)) | (Some(_), None) | (None, Some(_)) => FailureWrapper(Failure(description = "Init message received twice."))
          case _ => {
            println(s"New EventHandler & CommandHandler has been registered for service $serviceName")
            maybeEventHandler = Some(new EventHandler())
            maybeCommandHandler = Some(new CommandHandler(snapshot))
            Message.Empty
          }
        })
      } else if (esStream.message.isEvent) {
        val event: EventSourcedEvent = esStream.message.event.get
        val message: Message = maybeEventHandler match {
          case Some(eventHandler) => eventHandler.handleEvent(event)
          case None => FailureWrapper(Failure(description = "Unknown message received before init"))
        }
        EventSourcedStreamOut(message)
      } else {
        val command: Command = esStream.message.command.get
        val message: Message = maybeCommandHandler match {
          case Some(eventHandler) => eventHandler.handleCommand(command)
          case None => FailureWrapper(Failure(description = "Unknown message received before init"))
        }
        EventSourcedStreamOut(message)
      }
    }).filter(!_.message.isEmpty)
  }
}


class EventHandler() {
  def handleEvent(event: EventSourcedEvent) = {
    println(s"Received event ${event.sequence} with type ${event.payload.get.typeUrl}")
    Message.Empty
  }
}

class CommandHandler(val eventSourcedSnapshot: Option[EventSourcedSnapshot]) {
  var snapshot: Option[pbAny] = eventSourcedSnapshot.flatMap(_.snapshot)
  val serializerMap = Map(
    "com.example.shoppingcart.GetShoppingCart" -> ShoppingCart.Serializers.GetShoppingCartSerializer,
    // etc...
  )


  def handleCommand(command: Command) = {
    println(s"Received command ${command.name} with type ${command.payload.get.typeUrl}")

    if (command.name == "GetCart") {
      val bs: ByteString = ByteString(command.payload.get.value.toByteArray)
      val deserializedCommand: GetShoppingCart = ShoppingCart.Serializers.GetShoppingCartSerializer.deserialize(bs)
      val des: Option[(String, protobuf.ByteString)] = snapshot.flatMap(pbAny.unapply)
      val cartState: Cart = ShoppingCart.Serializers.CartSerializer.deserialize(ByteString(des.get._2.toByteArray))

      println(s"cartState $cartState")
      println(s"deserializedCommand $deserializedCommand")

//      cartState.items.filter(_.productId)

      //      val deserializedSnapshot = deserialize(snapshot)
      //      getCart(deserializedCommand, deserializedSnapshot)
      Reply(EventSourcedReply(command.id, Some(ClientAction(Action.Reply(io.cloudstate.entity.Reply(snapshot))))))
    } else {
      FailureWrapper(Failure(
        commandId = command.id,
        description = s"Unknown command named ${command.name}"))
    }
  }

  def getCart(request: GetShoppingCart, cart: Any) = cart
}