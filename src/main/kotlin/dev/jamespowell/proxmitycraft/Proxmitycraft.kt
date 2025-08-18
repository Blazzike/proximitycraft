package dev.jamespowell.proxmitycraft

import io.ktor.http.cio.websocket.WebSocketSession
import io.ktor.http.cio.websocket.close
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import net.fabricmc.api.ModInitializer
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents
import net.minecraft.text.ClickEvent
import net.minecraft.text.Text
import net.minecraft.util.Formatting
import java.net.URI
import java.util.*
import kotlin.math.pow
import kotlin.math.sqrt

data class ProximityLocation(
  val x: Double,
  val y: Double,
  val z: Double
) {
  fun distance(other: ProximityLocation): Double {
    return sqrt((x - other.x).pow(2.0) + (y - other.y).pow(2.0) + (z - other.z).pow(2.0))
  }

  fun calculateVolumeTo(other: ProximityLocation): Double {
    return 1.0 - Math.clamp((distance(other) / 100.0), 0.0, 1.0)
  }
}

data class SymmetricPair<A, B>(val first: A, val second: B) {
  override fun equals(other: Any?): Boolean {
    if (this === other) return true
    if (other !is SymmetricPair<*, *>) return false

    return (first == other.first && second == other.second) ||
        (first == other.second && second == other.first)
  }

  override fun hashCode(): Int {
    // Make the hash independent of order
    return first.hashCode() + second.hashCode()
  }
}

data class ProximityUser(
  val name: String,
  val voiceUuid: UUID,
  val playerUuid: UUID,
  var location: ProximityLocation,
) {
  var webSocketClient: ProximityWebSocketClient? = null
}

val voicePlayers = mutableSetOf<ProximityUser>()
val volumes = mutableMapOf<SymmetricPair<UUID, UUID>, Double>()
var tickIndex = 0

class Proxmitycraft : ModInitializer {
  override fun onInitialize() {
    CoroutineScope(Dispatchers.IO).launch {
      startWebsocketServer()
    }

    ServerPlayerEvents.JOIN.register { player ->
      val voiceUuid = UUID.randomUUID()
      val proximityUser = ProximityUser(
        name = player.name.string,
        voiceUuid = voiceUuid,
        playerUuid = player.uuid,
        location = ProximityLocation(
          x = player.x,
          y = player.y,
          z = player.z
        ),
      )

      voicePlayers.add(
        proximityUser
      )

      player.sendMessage(
        Text.literal("You must connect to the proximity chat by clicking ")
          .append(
            Text.literal("here")
              .styled { style ->
                style.withColor(Formatting.BLUE)
                  .withUnderline(true)
                  .withClickEvent(ClickEvent.OpenUrl(URI.create("https://proximitycraft.com/voice/${voiceUuid}")))
              }
          )
          .append(Text.literal("."))
      )

      for (otherProximityUser in voicePlayers) {
        if (otherProximityUser == proximityUser) {
          continue
        }

        val pair = SymmetricPair(proximityUser.voiceUuid, otherProximityUser.voiceUuid)
        volumes[pair] = proximityUser.location.calculateVolumeTo(otherProximityUser.location)
        if (volumes[pair] != 0.0) {
          CoroutineScope(Dispatchers.IO).launch {
            otherProximityUser.webSocketClient?.updateVolume(proximityUser, 0.0, volumes[pair]!!, true)
            proximityUser.webSocketClient?.updateVolume(otherProximityUser, 0.0, volumes[pair]!!, false)
          }
        }
      }
    }

    ServerPlayerEvents.LEAVE.register { player ->
      val proximityUser = voicePlayers.find { it.playerUuid == player.uuid }!!
      proximityUser.webSocketClient?.handleLeave()

      for (otherProximityUser in voicePlayers) {
        val pair = SymmetricPair(proximityUser.voiceUuid, otherProximityUser.voiceUuid)
        if (volumes[pair] != 0.0) {
          CoroutineScope(Dispatchers.IO).launch {
            otherProximityUser.webSocketClient?.updateVolume(proximityUser, volumes[pair]!!, 0.0, false)
          }
        }

        volumes.remove(pair)
      }

      println("Removed user ${proximityUser.name}")
    }

    ServerTickEvents.END_WORLD_TICK.register { world ->
      tickIndex++
      if (tickIndex % 20 != 0) {
        return@register
      }

      world.players.forEach { player ->
        voicePlayers.find { it.playerUuid == player.uuid }?.let { proximityUser ->
          proximityUser.location = ProximityLocation(
            x = player.x,
            y = player.y,
            z = player.z
          )
        }
      }

      voicePlayers.forEach { proximityUser ->
        if (proximityUser.webSocketClient == null) {
          return@forEach
        }

        voicePlayers.forEach { otherProximityUser ->
          if (proximityUser == otherProximityUser) {
            return@forEach
          }

          if (otherProximityUser.webSocketClient == null) {
            println("User ${otherProximityUser.name} should be on voice but is not")

            return@forEach
          }

          val volume = proximityUser.location.calculateVolumeTo(otherProximityUser.location)
          val pair = SymmetricPair(proximityUser.voiceUuid, otherProximityUser.voiceUuid)
          val previousVolume = volumes.getOrDefault(pair, 0.0)
          if (previousVolume != volume) {
            CoroutineScope(Dispatchers.IO).launch {
              proximityUser.webSocketClient!!.updateVolume(otherProximityUser, previousVolume, volume, false)
              otherProximityUser.webSocketClient!!.updateVolume(proximityUser, previousVolume, volume, true)
            }

            volumes[pair] = volume
          }
        }

      }
    }
  }
}
