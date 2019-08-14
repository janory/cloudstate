package io.cloudstate.scala.support

import akka.NotUsed
import akka.stream.Materializer
import akka.stream.scaladsl.{Sink, Source}
import akka.util.ByteString
import com.example.shoppingcart._
import com.google.protobuf
import com.google.protobuf.any.{Any => pbAny}
import com.google.protobuf.empty.Empty
import io.cloudstate.entity.ClientAction.Action
import io.cloudstate.entity.{ClientAction, Command, Failure}
import io.cloudstate.eventsourced.EventSourcedStreamOut.Message
import io.cloudstate.eventsourced.EventSourcedStreamOut.Message.{Reply, Failure => FailureWrapper}
import io.cloudstate.eventsourced._
import scalapb.GeneratedMessage

import scala.concurrent.Future


class EventSourcedServiceImpl(implicit mat: Materializer, ch: CustomHandler) extends EventSourced {


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


  def handleCommand(command: Command, ch: CustomHandler) = {
    println(s"Received command ${command.name} with type ${command.payload.get.typeUrl}")

    if (command.name == "GetCart") {
      val bs: ByteString = ByteString(command.payload.get.value.toByteArray)
      val deserializedCommand: GetShoppingCart = ShoppingCart.Serializers.GetShoppingCartSerializer.deserialize(bs)
      val des: Option[(String, protobuf.ByteString)] = snapshot.flatMap(pbAny.unapply)
      val cartState: Cart = ShoppingCart.Serializers.CartSerializer.deserialize(ByteString(des.get._2.toByteArray))

      println(s"cartState $cartState")
      println(s"deserializedCommand $deserializedCommand")

      ch.handleCommand(command, snapshot.get)

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

trait CustomHandler {
  //  type T <: scalapb.GeneratedMessage with scalapb.Message[T]
    protected type B <: scalapb.GeneratedMessage with scalapb.Message[B]
  //  def getSerializer(url: String): Either[ScalapbProtobufSerializer[T], ScalapbProtobufSerializer[B]]

//  def tes[Z](url: String, clazz: Class[Z]): GeneratedMessageCompanion[Z]
//
//  //  val serMap: Map[(String, Class[Z]), ScalapbProtobufSerializer[Z]]
//  sealed trait Json[T] {
//    def ser(ba: Array[Byte]): T
//  }
//
//  def getJson[T](s: String): Json[T]
//
//  def handle() = {
//    val ret: Json[Nothing] = this.getJson("dsadas")
//  }

  protected def desCommand(typeUrl: String, payload: Array[Byte]): GeneratedMessage

  protected def deSerializerState(payload: Array[Byte]): B

  protected def serializerState(state: GeneratedMessage): Array[Byte] = {
    state.toByteArray
  }

//  def getService

//  def handle(command: GeneratedMessage, state: GeneratedMessage): Option[GeneratedMessage]
  protected def handle(commandName: String, command: GeneratedMessage, state: B): Option[B]

  def handleCommand(command: Command, localState: pbAny): Option[pbAny] = {
    val desedCommand: GeneratedMessage = desCommand(command.payload.get.typeUrl, command.payload.get.toByteArray)
    val desState: B = deSerializerState(localState.toByteArray)
    val mayBeNewState = handle(command.name, desedCommand, desState)
    mayBeNewState.map(st => pbAny.apply("", st.toByteString))
  }

}

class ShoppingCartImpl extends CustomHandler {
  type T = GetShoppingCart
  type B = Cart

//  final class JsonString(string: GetShoppingCart) extends Json[GetShoppingCart] {
//    def ser(ba: Array[Byte]) = string.companion.parseFrom(ba)
//  }
//  final class JsonInt(int: Cart) extends Json[Cart] {
//    def ser(ba: Array[Byte]) = int.companion.parseFrom(ba)
//  }
//
////  val methpd = GetShoppingCart[GetShoppingCart].toByteArray
//
//  override def getJson(s: String) = s match {
//    case "1" => new JsonString([])
//    case "2" => new JsonInt(1)
//  }


  //  type HA >: GetShoppingCart with Cart <: GeneratedMessage with scalapb.Message[_ >: GetShoppingCart with Cart] with Updatable[_ >: GetShoppingCart with Cart]
  //
  //  final class Ser1(x: GetShoppingCart) extends ProtobufSerializer[GetShoppingCart] {
  //    override def serialize(t: GetShoppingCart): ByteString = ???
  //    override def deserialize(bytes: ByteString): GetShoppingCart = ???
  //  }
  //
  //  final class Ser2(x: Cart) extends ProtobufSerializer[Cart] {
  //    override def serialize(t: Cart): ByteString = ???
  //    override def deserialize(bytes: ByteString): Cart = ???
  //  }
  //
  //  final class Ser2(x: Z) extends ProtobufSerializer[Z] {
  //    override def serialize(t: Z): ByteString = ???
  //    override def deserialize(bytes: ByteString): Z = ???
  //  }
  //
  //  override def tes(url: String): ProtobufSerializer[G] = {
  //    url match {
  //      case "Something" => new Ser1(???)
  //      case "Something2" => new Ser2(???)
  //    }
  //  }
  //
  //  override def getSerializer(url: String): Either[ScalapbProtobufSerializer[GetShoppingCart], ScalapbProtobufSerializer[Cart]] = {
  //    url match {
  //      case "Something" => Left(ShoppingCart.Serializers.GetShoppingCartSerializer)
  //      case "Something2" => Right(ShoppingCart.Serializers.CartSerializer)
  //    }
  //  }
  //  override def getSerializer(url: String): Either[ScalapbProtobufSerializer[GetShoppingCart], ScalapbProtobufSerializer[Cart]] = ???
  //
  //  override def tes[C](url: String): ProtobufSerializer[C] = ???
  //
  //  override val serMap: Map[(String, GetShoppingCart), ScalapbProtobufSerializer[GetShoppingCart]] = _
//  override def tes[Z](url: String, clazz: Class[Z]): GeneratedMessageCompanion[Z] = url match {
//    case "Something" => Cart.messageCompanion
//    //        case "Something2" => new Ser2(???)
//  }

  override def deSerializerState(payload: Array[Byte]): B = {
      Cart.parseFrom(payload)
  }

  override def desCommand(typeUrl: String, payload: Array[Byte]): GeneratedMessage = {
    typeUrl match {
      case "ava" => GetShoppingCart.parseFrom(payload)
      case "ava" => AddLineItem.parseFrom(payload)
    }
  }

  def handle(commandName: String, command: GeneratedMessage, state: B): Option[B] = {
    commandName match {
      case "GetCart" =>
        Some(state)
      case "RemoveItem" =>
        val req: RemoveLineItem = command.asInstanceOf
        println(s"remove $req")
        None
    }
  }
}
