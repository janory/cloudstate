package io.cloudstate.scala.support

import akka.actor.ActorSystem
import akka.http.scaladsl.UseHttp2.Always
import akka.http.scaladsl.model.{HttpRequest, HttpResponse}
import akka.http.scaladsl.{Http, HttpConnectionContext}
import akka.stream.{ActorMaterializer, Materializer}
import com.typesafe.config.ConfigFactory
import io.cloudstate.entity.EntityDiscoveryHandler
import io.cloudstate.eventsourced.EventSourcedHandler

import scala.concurrent.{ExecutionContext, Future}

private final class CloudState(serviceName: String, persistenceId: String, snapshotEvery: Int, entityHandler: EntityHandler) {

  private val conf = ConfigFactory
    .parseString("akka.http.server.preview.enable-http2 = on")
    .withFallback(ConfigFactory.defaultApplication())

  private val system = ActorSystem("CloudState", conf)

  def run(): Future[Http.ServerBinding] = {
    // Akka boot up code
    implicit val sys: ActorSystem = system
    implicit val mat: Materializer = ActorMaterializer()
    implicit val ec: ExecutionContext = sys.dispatcher

    // Create service handlers
    val service: HttpRequest => Future[HttpResponse] =
      EntityDiscoveryHandler.partial(new EntityDiscoveryServiceImpl(serviceName, persistenceId))
        .orElse(EventSourcedHandler.partial(new EventSourcedServiceImpl(entityHandler)))

    // Bind service handler servers to localhost:8080/8081
    val binding = Http().bindAndHandleAsync(
      service,
      interface = "127.0.0.1",
      port = 8080,
      connectionContext = HttpConnectionContext(http2 = Always))

    // report successful binding
    binding.foreach { binding =>
      println(s"gRPC server bound to: ${binding.localAddress}")
    }

    binding
  }
}
