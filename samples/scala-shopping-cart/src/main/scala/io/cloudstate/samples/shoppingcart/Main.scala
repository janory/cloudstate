package io.cloudstate.samples.shoppingcart

import akka.actor.ActorRef
import akka.util.ByteString
import com.example.shoppingcart.persistence.{ItemAdded, ItemRemoved}
import com.example.shoppingcart.{GetShoppingCart, _}
import com.google.protobuf.any.{Any => PbAny}
import com.google.protobuf.empty.Empty
import com.google.protobuf.{ByteString => PbByteString}
import io.cloudstate.eventsourced.EventSourcedStreamOut.Message
import io.cloudstate.scala.support.{Context, EntityHandler}
import scalapb.lenses.Updatable
import scalapb.{GeneratedMessage, Message}
import akka.util.{ByteString => AkkaBString}

import scala.concurrent.Future

object Main extends App {
 new EntityHandlerImpl()
   .start(serviceName = "com.example.shoppingcart.ShoppingCart", persistenceId = "shopping-cart", snapshotEvery =  5)
}

class EntityHandlerImpl extends EntityHandler {
  private val CartSerializer = ShoppingCart.Serializers.CartSerializer

  override type STATE = Cart

  override protected def setInitial(entityId: String): STATE = Cart()

  override def serializerState(state: STATE): PbAny =
    PbAny.apply(
      "type.googleapis.com/com.example.shoppingcart.Cart",
      PbByteString.copyFrom(CartSerializer.serialize(state).toArray)
    )

  override def deSerializerState(state: ByteString): STATE =
    CartSerializer.deserialize(state)

  override def deserializePayload(typeUrl: String, payload: ByteString): GeneratedMessage =
    typeUrl match {
      case "type.googleapis.com/com.example.shoppingcart.GetShoppingCart" => ShoppingCart.Serializers.GetShoppingCartSerializer.deserialize(payload)
      case "type.googleapis.com/com.example.shoppingcart.AddLineItem" => ShoppingCart.Serializers.AddLineItemSerializer.deserialize(payload)
      case "type.googleapis.com/com.example.shoppingcart.RemoveLineItem" => ShoppingCart.Serializers.RemoveLineItemSerializer.deserialize(payload)
      case "type.googleapis.com/com.example.shoppingcart.persistence.ItemAdded" => ItemAdded.messageCompanion.parseFrom(payload.toArray)
      case "type.googleapis.com/com.example.shoppingcart.persistence.ItemRemoved" => ItemRemoved.messageCompanion.parseFrom(payload.toArray)
  }

  override def handleCommand(commandName: String, command: GeneratedMessage, ctx: Context): Option[STATE] = commandName match {
    case "GetCart" =>
      val getShoppingCart = command.asInstanceOf[GetShoppingCart]
      println(s"Got GetCart command with the following parameters $getShoppingCart")
      localState
    case "AddItem" =>
      val addLineItem = command.asInstanceOf[AddLineItem]
      println(s"Got AddItem command with the following parameters $addLineItem")
//      Emit new Event ItemAdded
//      ctx.emit(ItemAdded)
      None
    case "RemoveItem" =>
      val removeLineItem = command.asInstanceOf[RemoveLineItem]
      println(s"Got RemoveItem command with the following parameters $removeLineItem")
//      Emit new Event
//      ctx.emit(ItemRemoved) ItemRemoved
      None
  }

  override def handleEvent(eventName: String, event: GeneratedMessage): Unit = eventName match {
    case "type.googleapis.com/com.example.shoppingcart.persistence.ItemAdded" =>
      val itemAdded = event.asInstanceOf[ItemAdded]
      println(s"Got ItemAdded event with the following parametes $itemAdded")

      val newItem = itemAdded.item.get

      val maybeExistingItem: Option[LineItem] = localState.flatMap(_.items.find(_.productId == newItem.productId))

      val createdOrUpdatedItem: LineItem = maybeExistingItem match {
        case Some(existingItem) => existingItem.copy(quantity = existingItem.quantity + 1)
        case None => LineItem(productId = newItem.productId,name = newItem.name, quantity = newItem.quantity)
      }

      localState = localState.map{ls => ls.copy(items = ls.items.filter(_.productId != createdOrUpdatedItem.productId) :+ createdOrUpdatedItem) }
    case "type.googleapis.com/com.example.shoppingcart.persistence.ItemRemoved" =>
      val itemRemoved = event.asInstanceOf[ItemRemoved]
      println(s"Got ItemRemoved event with the following parametes $itemRemoved")
  }

}

trait SecondEntityHandler[State, A, B, C] {
  val ctx: ActorRef = null
  protected var localState = Option.empty[State]

  type AllCommandTypes = A with B with C

  def deserializePayload(typeUrl: String, payload: AkkaBString): AllCommandTypes


  def getCommandHandler(commandName: String): AllCommandTypes => Future[GeneratedMessage]

  def handleCommand() = {
    val handler = getCommandHandler("")
    val sg: AllCommandTypes = deserializePayload("", null)
    handler.apply(sg)
  }
}

class SecondEntityHandlerImpl extends SecondEntityHandler[Cart, GetShoppingCart, AddLineItem, RemoveLineItem] with ShoppingCart {

   def getCommandHandler(commandName: String): GetShoppingCart with AddLineItem with RemoveLineItem => Future[GeneratedMessage] = {
    commandName match {
      case "GetCart" => getCart
      case "AddItem" => addItem
      case "RemoveItem" => removeItem
    }
  }

  override def addItem(in: AddLineItem): Future[Empty] = {
    ctx ! "ItemAdded"
    Future.successful(Empty())
  }

  override def removeItem(in: RemoveLineItem): Future[Empty] = {
    ctx ! "ItemRemoved"
    Future.successful(Empty())
  }


  override def getCart(in: GetShoppingCart): Future[Cart] = {
    Future.successful(localState.get)
  }

   def dsadsa(typeUrl: String, payload: AkkaBString): GetShoppingCart with AddLineItem with RemoveLineItem  = typeUrl match {
    case "type.googleapis.com/com.example.shoppingcart.GetShoppingCart" => GetShoppingCart()
    case "type.googleapis.com/com.example.shoppingcart.AddLineItem" => AddLineItem()
    case "type.googleapis.com/com.example.shoppingcart.RemoveLineItem" => RemoveLineItem()
   }
}