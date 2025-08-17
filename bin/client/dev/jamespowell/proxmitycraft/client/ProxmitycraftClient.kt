package dev.jamespowell.proxmitycraft.client

import net.fabricmc.api.ClientModInitializer
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.minecraft.client.gui.screen.multiplayer.ConnectScreen
import net.minecraft.client.network.CookieStorage
import net.minecraft.client.network.ServerAddress
import net.minecraft.client.network.ServerInfo

class ProxmitycraftClient : ClientModInitializer {
  private var tickCount = 0

  override fun onInitializeClient() {
    ClientTickEvents.END_CLIENT_TICK.register { client ->
      if (tickCount == 60) {
        ConnectScreen.connect(
          null,
          client,
          ServerAddress("localhost", 25565),
          ServerInfo("MCTraveler", "localhost", ServerInfo.ServerType.OTHER),
          false,
          null
        )
      }

      tickCount++
    }
  }
}
