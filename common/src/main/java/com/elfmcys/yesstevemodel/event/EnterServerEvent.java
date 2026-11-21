package com.elfmcys.yesstevemodel.event;

import com.elfmcys.yesstevemodel.YesSteveModel;
import com.elfmcys.yesstevemodel.model.ServerModelManager;
import com.elfmcys.yesstevemodel.network.NetworkHandler;
import com.elfmcys.yesstevemodel.network.message.S2CSetModelAndTexturePacket;
import com.elfmcys.yesstevemodel.network.message.S2CSyncAuthModelsPacket;
import com.elfmcys.yesstevemodel.network.message.S2CSyncStarModelsPacket;
import com.elfmcys.yesstevemodel.network.message.S2CVersionCheckPacket;
import dev.architectury.event.events.common.PlayerEvent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;

public final class EnterServerEvent {

    private EnterServerEvent() {
    }

    public static void register() {
        PlayerEvent.PLAYER_JOIN.register(player -> {
            if (!YesSteveModel.isAvailable()) {
                return;
            }
            NetworkHandler.sendToClientPlayer(new S2CVersionCheckPacket(), player);
            CapabilityEvent.getModelInfoCap(player).ifPresent(modelInfoCap -> {
                if (!NetworkHandler.isPlayerConnected(player) && !modelInfoCap.isMandatory()) {
                    modelInfoCap.markDirty();
                    return;
                }
                modelInfoCap.stopAnimation(player);
                Optional<S2CSetModelAndTexturePacket> optional = modelInfoCap.createSyncMessage(player, false);
                Consumer<? super S2CSetModelAndTexturePacket> consumer = message -> {
                    NetworkHandler.sendToClientPlayer(message, player);
                    // 同时广播给所有在线玩家：别人此刻可能离得很远、客户端世界里还没有这个实体，
                    // 客户端会按 UUID 记住，等实体进入视野时自己贴上去。
                    NetworkHandler.sendToAll(message);
                };
                Objects.requireNonNull(modelInfoCap);
                optional.ifPresentOrElse(consumer, modelInfoCap::markDirty);
            });
            CapabilityEvent.getAuthModelsCap(player).ifPresent(authModelsCap -> {
                for (String modelId : ServerModelManager.getAuthModels()) {
                    authModelsCap.addModel(modelId);
                }
                NetworkHandler.sendToClientPlayer(new S2CSyncAuthModelsPacket(authModelsCap.getAuthModels()), player);
            });
            CapabilityEvent.getStarModelsCap(player).ifPresent(starModelsCap -> NetworkHandler.sendToClientPlayer(new S2CSyncStarModelsPacket(starModelsCap.getStarModels()), player));

            // 补发：后进服的玩家也要拿到「已在线的其他人」穿的模型。
            // 别人进服时的广播发生在这个玩家上线之前，他不会收到，于是会出现「一边能看到、另一边是原版」。
            CapabilityEvent.replayAllModelsTo(player);
        });
    }
}