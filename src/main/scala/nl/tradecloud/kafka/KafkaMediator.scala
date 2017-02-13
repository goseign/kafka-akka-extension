package nl.tradecloud.kafka

import akka.actor._
import akka.event.LoggingReceive
import nl.tradecloud.kafka.command.{Publish, Subscribe}
import nl.tradecloud.kafka.config.KafkaConfig

class KafkaMediator(
    extendedSystem: ExtendedActorSystem,
    config: KafkaConfig
) extends Actor with ActorLogging {

  def receive: Receive = LoggingReceive {
    case cmd: Subscribe =>
      startConsumer(cmd, sender())
    case cmd: Publish =>
      publisher(cmd.topic) forward cmd
  }

  private[this] def publisher(topic: String): ActorRef = {
    context.child(KafkaPublisher.name(topic)).getOrElse {
      context.actorOf(
        KafkaPublisher.props(
          extendedSystem = extendedSystem,
          config = config,
          topic = topic
        ),
        KafkaPublisher.name(topic)
      )
    }
  }

  private[this] def startConsumer(subscribe: Subscribe, subscribeSender: ActorRef): ActorRef = {
    context.actorOf(
      KafkaConsumer.props(
        extendedSystem = extendedSystem,
        config = config,
        subscribe = subscribe,
        subscribeSender = subscribeSender
      )
    )
  }
}

object KafkaMediator {
  final val name: String = "kafka-mediator"

  def props(
      extendedSystem: ExtendedActorSystem,
      config: KafkaConfig
  ): Props = {
    Props(
      classOf[KafkaMediator],
      extendedSystem,
      config
    )
  }

}