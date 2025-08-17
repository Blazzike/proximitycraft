package dev.jamespowell.proxmitycraft

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import net.fabricmc.api.ModInitializer
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents
import java.util.UUID
import net.minecraft.text.Text
import net.minecraft.text.ClickEvent
import net.minecraft.util.Formatting
import java.net.URI

data class ProximityUser(
  val name: String,
  val voiceUuid: UUID,
  val playerUuid: UUID
)

val voicePlayers = mutableSetOf<ProximityUser>()
var tickIndex = 0

class Proxmitycraft : ModInitializer {
  override fun onInitialize() {
    CoroutineScope(Dispatchers.IO).launch {
      startWebsocketServer();
    }

    ServerPlayerEvents.JOIN.register { player ->
      val voiceUuid = UUID.randomUUID();
      voicePlayers.add(ProximityUser(
        name = player.name.string,
        voiceUuid = voiceUuid,
        playerUuid = player.uuid
      ))

      player.sendMessage(
        Text.literal("You must connect to the proximity chat by clicking ")
          .append(
            Text.literal("here")
              .styled { style ->
                style.withColor(Formatting.BLUE)
                  .withUnderline(true)
                  .withClickEvent(ClickEvent.OpenUrl(URI.create("https://localhost:3000/voice/${voiceUuid}")))
              }
          )
          .append(Text.literal("."))
      )
    }

    ServerPlayerEvents.LEAVE.register { player ->
      voicePlayers.removeIf { it.playerUuid == player.uuid }
    }

    ServerTickEvents.END_WORLD_TICK.register { world ->
      tickIndex++
      if (tickIndex % 20 != 0) {
        return@register
      }


      world.players.forEach { player ->
        println("${player.name.content} is at ${player.x} ${player.y} ${player.z}")
      }
    }
  }
}
