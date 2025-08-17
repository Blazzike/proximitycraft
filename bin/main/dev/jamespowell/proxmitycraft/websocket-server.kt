package dev.jamespowell.proxmitycraft

import io.ktor.application.*
import io.ktor.http.*
import io.ktor.http.cio.websocket.*
import io.ktor.response.*
import io.ktor.routing.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.websocket.*
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import java.security.KeyStore
import java.util.UUID

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
  val users: List<User>? = null,
  val offer: SessionDescription? = null,
  val answer: SessionDescription? = null,
  val candidate: ICECandidate? = null
)


@Serializable
data class User(
  val username: String,
  @Serializable(with = UUIDSerializer::class)
  val sessionId: UUID
)

class WebRTCServer {
  private val users = mutableMapOf<WebSocketSession, User>()

  private val json = Json { ignoreUnknownKeys = true }

  private suspend fun broadcast(message: Message, excludeWs: WebSocketSession? = null) {
    val messageStr = json.encodeToString(message)

    users.keys.forEach { ws ->
      if (ws != excludeWs) {
        try {
          ws.send(Frame.Text(messageStr))
        } catch (e: Exception) {
          println("Error sending message to user: ${e.message}")
        }
      }
    }
  }

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
                "leave" -> handleLeave(session)
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
    val sessionId = message.fromSessionId ?: return

    // Check if username is already taken in the room
    val uuidTaken = users.values.any { it.sessionId == sessionId }
    if (uuidTaken) {
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

    if (!voicePlayers.any { it.voiceUuid == sessionId }) {
      session.send(
        Frame.Text(
          json.encodeToString(
            Message(
              type = "error",
              message = "Invalid session ID"
            )
          )
        )
      )
      return
    }

    // Add user to room

    // Send join confirmation with peer ID and current users
    session.send(
      Frame.Text(
        json.encodeToString(
          Message(
            type = "joined",
            username = voicePlayers.find { it.voiceUuid == sessionId }!!.name,
            users = users.values.toList()
          )
        )
      )
    )

    val sessionUsername = voicePlayers.find { it.voiceUuid == sessionId }!!.name
    users[session] = User(sessionUsername, sessionId)

    // Notify others in room
    broadcast(
      Message(
        type = "user-joined",
        username = sessionUsername,
        sessionId = sessionId
      ), session
    )

    println("User $sessionUsername ($sessionId) joined")
  }

  private suspend fun handleWebRTCSignaling(session: WebSocketSession, message: Message) {
    val user = users[session] ?: return

    val peer = users.entries.find { it.value.sessionId == message.targetSessionId }
    if (peer != null) {
      try {
        val forwardedMessage = message.copy(
          fromSessionId = user.sessionId,
          fromUsername = user.username
        )

        peer.key.send(Frame.Text(json.encodeToString(forwardedMessage)))
      } catch (e: Exception) {
        println("Error forwarding message: ${e.message}")
      }
    }
  }

  private suspend fun handleLeave(session: WebSocketSession) {
    handleDisconnect(session)
  }

  private suspend fun handleDisconnect(session: WebSocketSession) {
    val user = users[session] ?: return

    // Clean up empty rooms
    broadcast(
      Message(
        type = "user-left",
        username = user.username,
        sessionId = user.sessionId
      )
    )

    users.remove(session)
    println("User ${user.username} (${user.sessionId}) left")
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
          call.respondText("YO!", ContentType.Text.Html
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

