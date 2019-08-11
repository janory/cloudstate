package io.cloudstate.scala.support

import akka.NotUsed
import akka.stream.Materializer
import akka.stream.scaladsl.Source
import io.cloudstate.eventsourced.{EventSourced, EventSourcedStreamIn, EventSourcedStreamOut}

class EventSourcedServiceImpl(implicit mat: Materializer) extends EventSourced {
  import mat.executionContext

  override def handle(in: Source[EventSourcedStreamIn, NotUsed]): Source[EventSourcedStreamOut, NotUsed] = ???
}
