package nl.tradecloud.kafka

import akka.actor.ActorSystem
import akka.protobuf.ByteString
import akka.remote.WireFormats.SerializedMessage
import akka.serialization.{SerializationExtension, SerializerWithStringManifest}
import org.apache.kafka.common.errors.SerializationException

import scala.util.control.NonFatal

// COPIED FROM AKKA: https://github.com/akka/akka/blob/master/akka-remote/src/main/scala/akka/remote/MessageSerializer.scala
object KafkaMessageSerializer {
  /**
   * Uses Akka Serialization for the specified ActorSystem to transform the given MessageProtocol to a message
   */
  def deserialize(system: ActorSystem, messageProtocol: SerializedMessage): AnyRef = {
    SerializationExtension(system).deserialize(
      messageProtocol.getMessage.toByteArray,
      messageProtocol.getSerializerId,
      if (messageProtocol.hasMessageManifest) messageProtocol.getMessageManifest.toStringUtf8 else ""
    ).get
  }

  /**
   * Uses Akka Serialization for the specified ActorSystem to transform the given message to a MessageProtocol
   */
  def serialize(system: ActorSystem, message: AnyRef): SerializedMessage = {
    val s = SerializationExtension(system)
    val serializer = s.findSerializerFor(message)
    val builder = SerializedMessage.newBuilder

    try {
      builder.setMessage(ByteString.copyFrom(serializer.toBinary(message)))
      builder.setSerializerId(serializer.identifier)
      serializer match {
        case ser2: SerializerWithStringManifest =>
          val manifest = ser2.manifest(message)
          if (manifest != "")
            builder.setMessageManifest(ByteString.copyFromUtf8(manifest))
        case _ =>
          if (serializer.includeManifest)
            builder.setMessageManifest(ByteString.copyFromUtf8(message.getClass.getName))
      }
      builder.build
    } catch {
      case NonFatal(e) =>
        throw new SerializationException(s"Failed to serialize akka message [${message.getClass}] " +
          s"using serializer [${serializer.getClass}].", e)
    }
  }
}
