package dev.jamespowell.proxmitycraft.mixin

import net.minecraft.network.ClientConnection
import net.minecraft.server.PlayerManager
import net.minecraft.server.network.ConnectedClientData
import net.minecraft.server.network.ServerPlayerEntity
import org.spongepowered.asm.mixin.Mixin
import org.spongepowered.asm.mixin.injection.At
import org.spongepowered.asm.mixin.injection.Inject
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo

@Mixin(PlayerManager::class)
class PlayerManagerMixin {

  @Inject(
    method = ["onPlayerConnect"],
    at = [At(
      value = "INVOKE",
      target = "Lnet/minecraft/server/PlayerManager;sendCommandTree(Lnet/minecraft/server/network/ServerPlayerEntity;)V"
    )],
    cancellable = true
  )
  private fun onPlayerConnectMid(
    connection: ClientConnection,
    player: ServerPlayerEntity,
    clientData: ConnectedClientData,
    ci: CallbackInfo
  ) {
    // This will inject right before sendCommandTree is called
    println("Player connecting: ${player.gameProfile.name}")

    // Call sendWorldInfo with player and world
    (this as PlayerManager).sendWorldInfo(player, player.world)
    player.world.onPlayerConnected(player)


    // Add your custom logic here - some of the connection setup has already happened
    // For example, the player entity exists and some initialization is done

    // Let the connection process complete normally
    ci.cancel();
  }

  // Alternative: Inject at a specific line number (less reliable across versions)
  // @Inject(method = ["onPlayerConnect"], at = [At(value = "HEAD", shift = At.Shift.AFTER, by = 10)], cancellable = true)

  // Alternative: Inject before any method call
  // @Inject(method = ["onPlayerConnect"], at = [At(value = "INVOKE", target = "Lnet/minecraft/server/PlayerManager;sendToAll(Lnet/minecraft/text/Text;)V")], cancellable = true)
}
