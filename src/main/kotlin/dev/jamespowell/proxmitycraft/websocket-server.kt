package dev.jamespowell.proxmitycraft

import io.ktor.application.*
import io.ktor.http.*
import io.ktor.http.cio.websocket.*
import io.ktor.response.*
import io.ktor.routing.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.websocket.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import java.security.KeyStore
import java.util.*

@Serializable
data class ICECandidate(
  val candidate: String,
  val sdpMid: String? = null,
  val sdpMLineIndex: Int? = null,
  val usernameFragment: String? = null
)

@Serializable
data class SessionDescription(
  val sdp: String,
  val type: String? = null
)

object UUIDSerializer : KSerializer<UUID> {
  override val descriptor: SerialDescriptor =
    PrimitiveSerialDescriptor("UUID", PrimitiveKind.STRING)

  override fun serialize(encoder: Encoder, value: UUID) {
    encoder.encodeString(value.toString())
  }

  override fun deserialize(decoder: Decoder): UUID {
    return UUID.fromString(decoder.decodeString())
  }
}


@Serializable
data class Message(
  val type: String,
  @Serializable(with = UUIDSerializer::class)
  val sessionId: UUID? = null,
  @Serializable(with = UUIDSerializer::class)
  val targetSessionId: UUID? = null,
  @Serializable(with = UUIDSerializer::class)
  val fromSessionId: UUID? = null,
  val fromUsername: String? = null,
  val username: String? = null,
  val message: String? = null,
  val shouldInitiate: Boolean? = null,
//  val users: List<User>? = null,
  val offer: SessionDescription? = null,
  val answer: SessionDescription? = null,
  val candidate: ICECandidate? = null,
  val volume: Double? = null
)

private val json = Json { ignoreUnknownKeys = true }

class ProximityWebSocketClient(
  val webSocket: WebSocketSession,
  val proximityUser: ProximityUser
) {
  suspend fun updateVolume(other: ProximityUser, previousVolume: Double, volume: Double, shouldInitiate: Boolean) {
    if (previousVolume == 0.0) { // we need to connect the two peers
      send(
        Message(
          type = "user-joined",
          username = other.name,
          sessionId = other.voiceUuid,
          shouldInitiate = shouldInitiate,
          volume = volume
        )
      )

      return
    }

    if (volume == 0.0) { // we need to disconnect the two peers
      send(
        Message(
          type = "user-left",
          sessionId = other.voiceUuid
        )
      )

      return
    }

    send(
      Message(
        type = "update-volume",
        sessionId = other.voiceUuid,
        volume = volume
      )
    )
  }

  fun handleLeave() {
    CoroutineScope(Dispatchers.IO).launch {
      voicePlayers.remove(proximityUser)
    }

    proximityUser.webSocketClient = null
  }

  suspend fun send(message: Message) {
    webSocket.send(Frame.Text(json.encodeToString(message)))
  }
}

private fun findVoicePlayer(session: WebSocketSession): ProximityUser? {
  return voicePlayers.find { it.webSocketClient?.webSocket == session }
}

private fun findVoicePlayer(sessionId: UUID): ProximityUser? {
  return voicePlayers.find { it.voiceUuid == sessionId }
}

class WebRTCServer {
  suspend fun handleWebSocket(session: WebSocketSession) {
    println("New WebSocket connection")

    try {
      for (frame in session.incoming) {
        when (frame) {
          is Frame.Text -> {
            try {
              val messageText = frame.readText()
              val message = json.decodeFromString<Message>(messageText)

              when (message.type) {
                "join" -> handleJoin(session, message)
                "offer", "answer", "ice-candidate" -> handleWebRTCSignaling(session, message)
              }
            } catch (e: Exception) {
              println("Error handling message: $e")
            }
          }

          else -> {}
        }
      }
    } catch (e: Exception) {
      println("WebSocket error: ${e.message}")
    } finally {
      handleDisconnect(session)
    }
  }

  private suspend fun handleJoin(session: WebSocketSession, message: Message) {
    val sessionId = message.fromSessionId
    if (sessionId == null) {
      session.send(
        Frame.Text(
          json.encodeToString(
            Message(
              type = "error",
              message = "Missing session ID"
            )
          )
        )
      )

      return
    }

    val voicePlayer = findVoicePlayer(sessionId)
    if (voicePlayer == null) {
      session.send(
        Frame.Text(
          json.encodeToString(
            Message(
              type = "error",
              message = "No matching player for session ID"
            )
          )
        )
      )

      return
    }

    if (voicePlayer.webSocketClient != null) {
      session.send(
        Frame.Text(
          json.encodeToString(
            Message(
              type = "error",
              message = "You're already in the voice room"
            )
          )
        )
      )

      return
    }

    voicePlayer.webSocketClient = ProximityWebSocketClient(
      session,
      voicePlayer
    )

    for (other in voicePlayers) {
      if (other == voicePlayer) {
        continue
      }

      val volume = other.location.calculateVolumeTo(voicePlayer.location)
      volumes[SymmetricPair(voicePlayer.voiceUuid, other.voiceUuid)] = volume
      if (volume == 0.0) {
        continue
      }

      other.webSocketClient?.updateVolume(
        voicePlayer,
        0.0,
        volume,
        false
      )

      voicePlayer.webSocketClient?.updateVolume(
        other,
        0.0,
        volume,
        true
      )
    }

    // Send join confirmation with peer ID and current users
    session.send(
      Frame.Text(
        json.encodeToString(
          Message(
            type = "joined",
            username = voicePlayer.name,
          )
        )
      )
    )

    // Notify others in room
//    broadcast(
//      Message(
//        type = "user-joined",
//        username = sessionUsername,
//        sessionId = sessionId
//      ), session
//    )

    println("User ${voicePlayer.name} ($sessionId) joined")
  }

  private suspend fun handleWebRTCSignaling(session: WebSocketSession, message: Message) {
    val user = findVoicePlayer(session) ?: return

    val peer = voicePlayers.find { it.voiceUuid == message.targetSessionId }
    if (peer != null) {
      try {
        val forwardedMessage = message.copy(
          fromSessionId = user.voiceUuid,
          fromUsername = user.name
        )

        peer.webSocketClient!!.send(forwardedMessage)
      } catch (e: Exception) {
        println("Error forwarding message: ${e.message}")
      }
    }
  }

  private fun handleDisconnect(session: WebSocketSession) {
    val proximityUser = findVoicePlayer(session) ?: return

    // Clean up empty rooms
//    broadcast(
//      Message(
//        type = "user-left",
//        username = user.username,
//        sessionId = user.sessionId
//      )
//    )

    proximityUser.webSocketClient = null
    println("User ${proximityUser.name} (${proximityUser.voiceUuid}) left")
  }
}

fun startWebsocketServer() {
  val port = System.getenv("PORT")?.toIntOrNull() ?: 1234
  val webRTCServer = WebRTCServer()

  // Load SSL keystore from classpath
  val keystoreStream = object {}.javaClass.getResourceAsStream("/keystore.p12")
    ?: throw IllegalStateException("Could not find keystore.p12 in resources")
  val keyStore = KeyStore.getInstance("PKCS12")
  keyStore.load(keystoreStream, "changeit".toCharArray())

  val environment = applicationEngineEnvironment {
    sslConnector(
      keyStore = keyStore,
      keyAlias = "proximitycraft",
      keyStorePassword = { "changeit".toCharArray() },
      privateKeyPassword = { "changeit".toCharArray() }
    ) {
      this.port = port
      this.host = "0.0.0.0"
    }
    module {
      install(WebSockets)
      routing {
        get("/") {
          call.respondText(
            "YO!", ContentType.Text.Html
          )
        }
        get("/health") {
          call.respondText("ok")
        }
        webSocket("/") {
          webRTCServer.handleWebSocket(this)
        }
      }
    }
  }

  embeddedServer(Netty, environment).start(wait = true)

  println("WebSocket server running at:")
  println("  HTTPS: https://localhost:$port")
  println("  WSS: wss://localhost:$port")
}

