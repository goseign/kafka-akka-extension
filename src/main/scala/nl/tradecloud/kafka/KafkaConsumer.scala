package nl.tradecloud.kafka

import java.util.concurrent.TimeUnit

import akka.Done
import akka.actor._
import akka.event.LoggingReceive
import akka.kafka.ConsumerMessage.CommittableMessage
import akka.kafka.scaladsl.Consumer
import akka.kafka.{ConsumerSettings, Subscriptions}
import akka.pattern.ask
import akka.serialization.SerializationExtension
import akka.stream.scaladsl.Sink
import akka.stream.{ActorMaterializer, ActorMaterializerSettings, Materializer, Supervision}
import akka.util.Timeout
import nl.tradecloud.kafka.KafkaConsumer.{ConsumerStart, ConsumerTerminating}
import nl.tradecloud.kafka.command.Subscribe
import nl.tradecloud.kafka.config.KafkaConfig
import nl.tradecloud.kafka.exception.KafkaDeserializationException
import nl.tradecloud.kafka.SerializedMessage.SerializedMessageMsg
import nl.tradecloud.kafka.response.SubscribeAck
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.common.serialization.ByteArrayDeserializer

import scala.concurrent.duration._
import scala.concurrent.{Await, Future}
import scala.language.postfixOps

class KafkaConsumer(
    extendedSystem: ExtendedActorSystem,
    config: KafkaConfig,
    group: String,
    topics: Set[String]
) extends Actor with ActorLogging with Stash {
  implicit val dispatcher = context.system.dispatchers.lookup("dispatchers.kafka-dispatcher")

  val prefixedTopics: Set[String] = topics.map(config.topicPrefix + _)

  val decider: Supervision.Decider = {
    case e: KafkaDeserializationException =>
      log.error(e, "Message is not deserializable, resuming...")
      Supervision.Resume
    case e: Throwable =>
      log.error(e, "Exception occurred")
      Supervision.Stop
    case _ =>
      log.error("Unknown problem")
      Supervision.Stop
  }

  implicit val materializer: Materializer = ActorMaterializer(
    ActorMaterializerSettings(context.system)
      .withSupervisionStrategy(decider)
  )

  val serializer = SerializationExtension(context.system)

  var consumer: Option[Consumer.Control] = None

  override def preStart(): Unit = {
    context.system.scheduler.scheduleOnce(
      delay = FiniteDuration(10, TimeUnit.SECONDS),
      receiver = self,
      message = ConsumerStart
    )
  }

  def receive: Receive = LoggingReceive {
    case ConsumerStart =>
      log.info(
        "Start KafkaConsumer, with group={}, topics={}, prefixedTopics={}",
        group,
        topics.mkString(", "),
        prefixedTopics.mkString(", ")
      )

      val consumerSettings = ConsumerSettings(context.system, new ByteArrayDeserializer, new ByteArrayDeserializer)
        .withBootstrapServers(config.bootstrapServers)
        .withGroupId(group)
        .withProperty(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest")

      context.become(subscribing(consumerSettings))
      unstashAll()
    case msg =>
      stash()
  }

  def subscribing(consumerSettings: ConsumerSettings[Array[Byte], Array[Byte]]): Receive = LoggingReceive {
    case subscribe: Subscribe =>
      consumer = Some(
        Consumer
          .committableSource(consumerSettings, Subscriptions.topics(prefixedTopics))
          .map { message: CommittableMessage[Array[Byte], Array[Byte]] =>
            log.debug("Received message value={}, key={}", message.record.value, message.record.key)

            SerializedMessageMsg.parseFrom(message.record.value) match {
              case payload: SerializedMessageMsg =>
                message -> KafkaMessageSerializer.deserialize(
                  system = extendedSystem,
                  messageProtocol = payload
                )
              case _ =>
                log.error("Unable to deserialize msg={}", message.record.value)
                throw KafkaDeserializationException(s"Unable to deserialize msg ${message.record.value}")
            }
          }
          .mapAsync(2) { // sending and committing offset
            case (message: CommittableMessage[Array[Byte], Array[Byte]], msg: AnyRef) =>
              log.debug("Sending msg={}", msg)

              subscribe.ref.ask(message = msg)(timeout = Timeout(config.acknowledgeTimeout)).flatMap {
                case subscribe.acknowledgeMsg =>
                  log.debug("Committing offset={}", message.record.offset())

                  message.committableOffset.commitScaladsl()
                case resp =>
                  log.warning("Received invalid acknowledge msg={}", resp)

                  Future.successful(resp)
              }
          }
          .to(Sink.ignore)
          .run()
      )

      consumer.map(_.isShutdown).foreach(terminateWhenDone)
      context.become(running)
      context.watch(subscribe.ref)

      sender() ! SubscribeAck(subscribe)
  }

  private[this] def terminateWhenDone(result: Future[Done]): Unit = {
    result.onFailure {
      case e: Throwable =>
        log.error(e, e.getMessage)
        self ! PoisonPill
    }

    result.onSuccess {
      case _ =>
        log.info(
          "Stopping consumer with group={}, topics={}, prefixedTopics={}",
          group,
          topics.mkString(", "),
          prefixedTopics.mkString(", ")
        )

        self ! PoisonPill
    }
  }

  def running = LoggingReceive {
    case msg: Subscribe =>
      log.warning(
        "Consumer with group={}, topics={}, prefixedTopics={} already active",
        group,
        topics.mkString(", "),
        prefixedTopics.mkString(", ")
      )

      sender() ! SubscribeAck(msg)
    case msg: Terminated =>
      context.stop(self)
  }

  override def postStop(): Unit = {
    log.info(
      "Terminating kafka consumer, group={}, topics={}, prefixedTopics={}",
      group,
      topics.mkString(", "),
      prefixedTopics.mkString(", ")
    )
    context.parent ! ConsumerTerminating
    consumer.map(c => Await.ready(c.shutdown(), FiniteDuration(20, TimeUnit.SECONDS)))
  }
}

object KafkaConsumer {
  case object ConsumerTerminating
  case object ConsumerStart

  def name(
      group: String,
      topics: Set[String]
  ): String = s"kafka-consumer-$group-${topics.mkString("_")}"

  def props(
      extendedSystem: ExtendedActorSystem,
      config: KafkaConfig,
      group: String,
      topics: Set[String]
  ): Props = {
    Props(
      classOf[KafkaConsumer],
      extendedSystem,
      config,
      group,
      topics
    )
  }

}