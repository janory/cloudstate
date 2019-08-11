package io.cloudstate.scala.support

import akka.actor.ActorSystem
import akka.http.scaladsl.UseHttp2.Always
import akka.http.scaladsl.model.{HttpRequest, HttpResponse}
import akka.http.scaladsl.{Http, HttpConnectionContext}
import akka.stream.{ActorMaterializer, Materializer}
import com.typesafe.config.ConfigFactory
import io.cloudstate.entity.{EntityDiscovery, EntityDiscoveryHandler}
import io.cloudstate.eventsourced.EventSourcedHandler

import scala.concurrent.{ExecutionContext, Future}

object MainServer {

  def main(args: Array[String]): Unit = {
    // Important: enable HTTP/2 in ActorSystem's config
    // We do it here programmatically, but you can also set it in the application.conf
    val conf = ConfigFactory
      .parseString("akka.http.server.preview.enable-http2 = on")
      .withFallback(ConfigFactory.defaultApplication())
    val system = ActorSystem("HelloWorld", conf)
    new MainServer(system).run()
    // ActorSystem threads will keep the app alive until `system.terminate()` is called
  }
}

class MainServer(system: ActorSystem) {

  def run(): Future[Http.ServerBinding] = {
    // Akka boot up code
    implicit val sys: ActorSystem = system
    implicit val mat: Materializer = ActorMaterializer()
    implicit val ec: ExecutionContext = sys.dispatcher

    // Create service handlers
    val service: HttpRequest => Future[HttpResponse] =
      EntityDiscoveryHandler.partial(new EntityDiscoveryServiceImpl()) orElse EventSourcedHandler.partial(new EventSourcedServiceImpl())

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
