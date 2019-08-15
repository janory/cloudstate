package io.cloudstate.scala.support

import akka.NotUsed
import akka.stream.Materializer
import akka.stream.scaladsl.{Sink, Source}
import akka.util.ByteString
import com.example.shoppingcart.{GetShoppingCart, _}
import com.google.protobuf.any.{Any => pbAny}
import com.google.protobuf.empty.Empty
import com.google.protobuf.{ByteString => pbByteString}
import io.cloudstate.entity.ClientAction.Action
import io.cloudstate.entity.{ClientAction, Command, Failure}
import io.cloudstate.eventsourced.EventSourcedStreamOut.Message
import io.cloudstate.eventsourced.EventSourcedStreamOut.Message.{Reply, Failure => FailureWrapper}
import io.cloudstate.eventsourced._
import scalapb.{GeneratedMessage, Message}

import scala.concurrent.{Await, Future}
import scala.language.postfixOps


class EventSourcedServiceImpl(implicit mat: Materializer) extends EventSourced {


  override def handle(in: Source[EventSourcedStreamIn, NotUsed]): Source[EventSourcedStreamOut, NotUsed] = {
    var maybeEventHandler = Option.empty[EventHandler]
    var maybeCommandHandler = Option.empty[CommandHandler]

    in.to(Sink.onComplete(done => {
      println("Stream completed")
    }))

    in.map((esStream: EventSourcedStreamIn) => {
      if (esStream.message.isInit) {
        val init = esStream.message.init.get
        val serviceName = init.serviceName
        val snapshot: Option[EventSourcedSnapshot] = init.snapshot

        println(s"New entity created with entity id ${init.entityId}")

        EventSourcedStreamOut((maybeEventHandler, maybeCommandHandler) match {
          case (Some(_), Some(_)) | (Some(_), None) | (None, Some(_)) => FailureWrapper(Failure(description = "Init message received twice."))
          case _ => {
            println(s"New EventHandler & CommandHandler has been registered for service $serviceName")
            maybeEventHandler = Some(new EventHandler())
            maybeCommandHandler = Some(new CommandHandler(init.entityId, snapshot))
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

class CommandHandler(val entityId: String, val eventSourcedSnapshot: Option[EventSourcedSnapshot])(implicit mat: Materializer) {
  val cartSerializer = ShoppingCart.Serializers.CartSerializer

  var localState: pbAny = eventSourcedSnapshot match {
    case Some(snapshot) => snapshot.snapshot.get
    case None => pbAny.apply(s"type.googleapis.com/${classOf[Cart].getCanonicalName}", pbByteString.copyFrom(cartSerializer.serialize(Cart(List())).toArray))
  }


  def getCartFromState(localState: pbAny): Cart = {
    val (_, localeState) = pbAny.unapply(localState).getOrElse(throw new RuntimeException("Make this to a Failure response instead of exception"))
    ShoppingCart.Serializers.CartSerializer.deserialize(ByteString(localeState.toByteArray))
  }


  def handleCommand(command: Command) = {
    println(s"Received command ${command.name} with type ${command.payload.get.typeUrl}")

    if (command.name == "GetCart") {
      //      val bs: ByteString = ByteString(command.payload.get.value.toByteArray)
      //      val deserializedCommand = commandSerializer.deserialize(bs)
      //      val cartState = getCartFromState(localState)
      //
      //      println(s"cartState $cartState")
      //      println(s"deserializedCommand $deserializedCommand")

      //      cartState.items.filter(_.productId)

      //      val deserializedSnapshot = deserialize(snapshot)
      //      getCart(deserializedCommand, deserializedSnapshot)

      //      val response: pbAny = new ShoppingCartImpl(localState, None).callMethodByCommand(command)


      println(s"localstate $localState")

      new HandlerImpl().handleCommand(command, localState) match {
        case Some(response) => Reply(EventSourcedReply(command.id, Some(ClientAction(Action.Reply(io.cloudstate.entity.Reply(Some(response)))))))
        case None => Message.Empty
      }


    } else {
      FailureWrapper(Failure(
        commandId = command.id,
        description = s"Unknown command named ${command.name}"))
    }
  }

  def getCart(request: GetShoppingCart, cart: Any) = cart
}


trait Handler {


  protected type STATE <: scalapb.GeneratedMessage with scalapb.Message[STATE]
  protected type MSG1 <: scalapb.GeneratedMessage with scalapb.Message[MSG1]
  protected type MSG2 <: scalapb.GeneratedMessage with scalapb.Message[MSG2]
  protected type MSG3 <: scalapb.GeneratedMessage with scalapb.Message[MSG3]

  protected def deSerializerState(payload: ByteString): STATE

  protected def serializerState(payload: STATE): pbAny

  protected def deserializeCommand(typeUrl: String, payload: ByteString): scalapb.GeneratedMessage

  protected def handle(commandName: String, command: scalapb.GeneratedMessage, state: STATE): Option[STATE]

  protected val commandExecutor: Map[String, (MSG1 with MSG2 with MSG3, STATE) => Unit]

  def handleCommand(command: Command, localState: pbAny): Option[pbAny] = {
    val desedCommand: scalapb.GeneratedMessage = deserializeCommand(command.payload.get.typeUrl, ByteString(command.payload.get.toByteArray))

    val ls = pbAny.unapply(localState).get._2.toByteArray

    val desState: STATE = deSerializerState(ByteString(ls))

    val mayBeNewState = handle(command.name, desedCommand, desState)

    commandExecutor("").apply(null, desState)

    mayBeNewState.map(st => serializerState(st))

  }
}

class HandlerImpl extends Handler {
  override protected type STATE = Cart

  override protected def deSerializerState(payload: ByteString): STATE = ShoppingCart.Serializers.CartSerializer.deserialize(payload)

  override protected def serializerState(payload: STATE): pbAny = pbAny.apply(s"type.googleapis.com/${classOf[Cart].getCanonicalName}", payload.toByteString)

  override protected def deserializeCommand(typeUrl: String, payload: ByteString): GeneratedMessage = typeUrl match {
    case "type.googleapis.com/com.example.shoppingcart.GetShoppingCart" => ShoppingCart.Serializers.GetShoppingCartSerializer.deserialize(payload)
  }

  override protected def handle(commandName: String, command: GeneratedMessage, state: STATE): Option[STATE] = commandName match {
    case "GetCart" =>
      Some(Cart(List(LineItem("check this fake state"))))
    //      Some(state)
    case "RemoveItem" =>
      val req: RemoveLineItem = command.asInstanceOf
      println(s"remove $req")
      None
  }

  override type MSG1  = RemoveLineItem
  override type MSG2  = AddLineItem
  override type MSG3  = GetShoppingCart
//
//  val commandExecutor: Map[String, (MSG1 with MSG2 with MSG3, Cart) => Unit] = Map(
//    "RemoveItem" -> {
//      (command: RemoveLineItem, state: STATE) => println("cleanup successfully")
//    },
//    "Additem" -> {
//      (command: AddLineItem, state: STATE) => println("cleanup successfully")
//    },
//    "GetCart" -> {
//      (command: GetShoppingCart, state: STATE) => println("cleanup successfully")
//    })
  override protected val commandExecutor: Map[String, (RemoveLineItem with AddLineItem with GetShoppingCart, Cart) => Unit] = _
}

trait CustomHandler {

  def getStateSerializer(): (String, Any)

  def getSerializer(typeUrl: String): Any

  def callMethodByCommand(command: Command): pbAny

  //  def callMethodByCommand(command: Command): pbAny = {
  //    val methodToCall: Method = this.getClass.getMethod(command.name)
  //
  //    val serializer = getSerializer(command.payload.get.typeUrl).asInstanceOf[ScalapbProtobufSerializer]
  //
  //    val desCommand = serializer.deserialize(ByteString(command.payload.get.value.toByteArray))
  //
  //    val resultF: Future[Any] = methodToCall.invoke(this, desCommand.asInstanceOf[Object]).asInstanceOf[Future[Any]]
  //
  //    val result: Any = Await.result(resultF, 10 seconds)
  //
  //    val stateSer = getStateSerializer()
  //
  //    pbAny.apply(stateSer._1, pbByteString.copyFrom(stateSer._2.asInstanceOf[ScalapbProtobufSerializer].serialize(result).toArray))
  //  }
}

class ShoppingCartImpl(localState: pbAny, ctx: Option[pbAny])(implicit mat: Materializer) extends ShoppingCart with CustomHandler {

  //  val serializerMap = Map(
  //    "type.googleapis.com/com.example.shoppingcart.GetShoppingCart" -> ShoppingCart.Serializers.GetShoppingCartSerializer,
  //    "type.googleapis.com/com.example.shoppingcart.AddLineItem" -> ShoppingCart.Serializers.AddLineItemSerializer
  //  )

  override def addItem(in: AddLineItem): Future[Empty] = ???

  override def removeItem(in: RemoveLineItem): Future[Empty] = ???

  override def getCart(in: GetShoppingCart): Future[Cart] = {
    val res: Option[(String, pbByteString)] = pbAny.unapply(localState)

    val cartState = ShoppingCart.Serializers.CartSerializer.deserialize(ByteString(res.get._2.toByteArray))
    Future.successful(cartState)
  }

  override def callMethodByCommand(command: Command) = {
    import scala.concurrent.duration._
    val sgF = command.name match {
      case "GetCart" => {
        val bs: ByteString = ByteString(command.payload.get.value.toByteArray)
        val deserializedCommand = ShoppingCart.Serializers.GetShoppingCartSerializer.deserialize(bs)


        //          val commandSerializer = serializerMap.getOrElse(command.payload.get.typeUrl, throw new RuntimeException("Make this to a Failure response instead of exception"))
        //          val deserializedCommand = commandSerializer.deserialize(bs)
        getCart(deserializedCommand)
      }
      //        case "AddItem" => {
      //          val bs: ByteString = ByteString(command.payload.get.value.toByteArray)
      //          val commandSerializer = serializerMap.getOrElse(command.payload.get.typeUrl, throw new RuntimeException("Make this to a Failure response instead of exception"))
      //          val deserializedCommand = commandSerializer.deserialize(bs)
      //          addItem(deserializedCommand.asInstanceOf[AddLineItem])
      //        }
      case _ => throw new RuntimeException("Not implemented")
    }
    val sg: Cart = Await.result(sgF, 10 seconds)
    val serCart: ByteString = ShoppingCart.Serializers.CartSerializer.serialize(sg)
    pbAny.apply("type.googleapis.com/com.example.shoppingcart.Cart", pbByteString.copyFrom(serCart.toArray))
  }

  //  override def getStateSerializer(): (String, ScalapbProtobufSerializer[Any]) = ???
  //
  //  type SomeMessage[T <: scalapb.GeneratedMessage with scalapb.Message[T]] = ScalapbProtobufSerializer[T]

  //  val fd: SomeMessage[pbAny] = ShoppingCart.Serializers.GetShoppingCartSerializer

  //  def getSerializer[B](typeUrl: String): SomeMessage = typeUrl match {
  //    case "type.googleapis.com/com.example.shoppingcart.GetShoppingCart" => ShoppingCart.Serializers.GetShoppingCartSerializer
  //    case "type.googleapis.com/com.example.shoppingcart.AddLineItem" => ShoppingCart.Serializers.AddLineItemSerializer
  //  }

  //  type ShoppingCartSerializers = _ >: GetShoppingCart with AddLineItem <: scalapb.GeneratedMessage with scalapb.Message[_ >: GetShoppingCart with AddLineItem] with Updatable[_ >: GetShoppingCart with AddLineItem]


  override def getStateSerializer(): (String, Any) = {
    "type.googleapis.com/com.example.shoppingcart.Cart" -> ShoppingCart.Serializers.CartSerializer
  }

  override def getSerializer(typeUrl: String) = typeUrl match {
    case "type.googleapis.com/com.example.shoppingcart.GetShoppingCart" => ShoppingCart.Serializers.GetShoppingCartSerializer
  }
}