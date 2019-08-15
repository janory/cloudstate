package io.cloudstate.scala.support

import java.io.{DataInputStream, File, FileInputStream}

import akka.stream.Materializer
import com.google.protobuf.ByteString
import com.google.protobuf.empty.Empty
import io.cloudstate.entity._

import scala.concurrent.Future

class EntityDiscoveryServiceImpl(serviceName: String, persistenceId: String)(implicit mat: Materializer) extends EntityDiscovery {

  override def discover(in: ProxyInfo): Future[EntitySpec] = {
    // TODO make this idiomatic scala
    val file = new File(getClass.getResource("/user-function.desc").getFile)
    val bytes = new Array[Byte](file.length().toInt)
    val dis = new DataInputStream(new FileInputStream(file))
    dis.readFully(bytes)

    val userFunction = try ByteString.copyFrom(bytes) finally dis.close()

    val entities = Set(Entity(
      entityType = "cloudstate.eventsourced.EventSourced",
      serviceName = serviceName,
      persistenceId = persistenceId))

    val ep = EntitySpec(userFunction, entities.toList)

    Future.successful(ep)
  }

  override def reportError(in: UserFunctionError): Future[Empty] = {
    println(s"Error: ${in.message}")
    Future.successful(Empty())
  }
}
