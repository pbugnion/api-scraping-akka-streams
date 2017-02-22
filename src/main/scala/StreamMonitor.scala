
import akka.actor.{Actor, Props, ActorSystem}

import akka.stream.scaladsl.Flow
import akka.NotUsed

object StreamMonitor {

  // Messages received.
  case object ElementReceived

  def props(logEvery: Int)(logMessage: Int => Unit): Props = Props(
    classOf[StreamMonitor], logEvery, logMessage: Int => Unit)

  def monitor[T](logEvery: Int)(logMessage: Int => Unit)(implicit system: ActorSystem): Flow[T, T, NotUsed] = {
    val monitorActor = system.actorOf(props(logEvery)(logMessage))
    Flow[T].map { element =>
      monitorActor ! ElementReceived
      element
    }
  }
}

class StreamMonitor(logEvery: Int, logMessage: Int => Unit)
    extends Actor {
  import StreamMonitor._
  var numberElements = 0
  def receive: Receive = {
    case ElementReceived =>
      numberElements += 1
      if (numberElements % logEvery == 0) { logMessage(numberElements) }
  }
}
