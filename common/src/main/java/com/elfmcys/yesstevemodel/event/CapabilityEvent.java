package com.elfmcys.yesstevemodel.event;

import com.elfmcys.yesstevemodel.YesSteveModel;
import com.elfmcys.yesstevemodel.capability.AuthModelsCapability;
import com.elfmcys.yesstevemodel.capability.ModelInfoCapability;
import com.elfmcys.yesstevemodel.capability.ProjectileModelCapability;
import com.elfmcys.yesstevemodel.capability.StarModelsCapability;
import com.elfmcys.yesstevemodel.capability.VehicleModelCapability;
import com.elfmcys.yesstevemodel.config.ServerConfig;
import com.elfmcys.yesstevemodel.model.ServerModelManager;
import com.elfmcys.yesstevemodel.network.NetworkHandler;
import com.elfmcys.yesstevemodel.network.message.S2CSetModelAndTexturePacket;
import com.elfmcys.yesstevemodel.network.message.S2CSyncAuthModelsPacket;
import com.elfmcys.yesstevemodel.network.message.S2CSyncProjectileModelPacket;
import com.elfmcys.yesstevemodel.network.message.S2CSyncStarModelsPacket;
import com.elfmcys.yesstevemodel.network.message.S2CSyncVehicleModelPacket;
import com.elfmcys.yesstevemodel.network.message.S2CVersionCheckPacket;
import rip.ysm.api.capability.CapabilityLifecycle;
import dev.architectury.event.EventResult;
import dev.architectury.event.events.common.EntityEvent;
import dev.architectury.event.events.common.PlayerEvent;
import dev.architectury.event.events.common.TickEvent;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.level.Level;

public final class CapabilityEvent {

    private CapabilityEvent() {
    }

    public static void register() {
        PlayerEvent.PLAYER_CLONE.register(CapabilityEvent::onPlayerCloned);
        EntityEvent.ADD.register(CapabilityEvent::onEntityAdd);
        TickEvent.SERVER_POST.register(CapabilityEvent::onServerTick);
    }

    private static void onPlayerCloned(ServerPlayer oldPlayer, ServerPlayer newPlayer, boolean wasDeath) {
        if (!YesSteveModel.isAvailable()) {
            return;
        }
        CapabilityLifecycle.revive(oldPlayer);
        Optional<ModelInfoCapability> oldModelInfoCap = getModelInfoCap(oldPlayer);
        Optional<AuthModelsCapability> oldAuthModelsCap = getAuthModelsCap(oldPlayer);
        Optional<StarModelsCapability> oldStarModelsCap = getStarModelsCap(oldPlayer);
        CapabilityLifecycle.invalidate(oldPlayer);
        Optional<ModelInfoCapability> modelInfoCap = getModelInfoCap(newPlayer);
        Optional<AuthModelsCapability> authModelsCap = getAuthModelsCap(newPlayer);
        Optional<StarModelsCapability> starModelsCap = getStarModelsCap(newPlayer);
        modelInfoCap.ifPresent(newModelInfo -> {
            Objects.requireNonNull(newModelInfo);
            oldModelInfoCap.ifPresent(newModelInfo::copyFrom);
        });
        authModelsCap.ifPresent(newAuthModels -> {
            Objects.requireNonNull(newAuthModels);
            oldAuthModelsCap.ifPresent(newAuthModels::copyFrom);
        });
        starModelsCap.ifPresent(newStarModels -> {
            Objects.requireNonNull(newStarModels);
            oldStarModelsCap.ifPresent(newStarModels::copyFrom);
        });
        // 重生后客户端世界里其他玩家的实体是重建的，需要重新把别人的模型推给他
        replayAllModelsTo(newPlayer);
        // 自己重生后实体 id 变了，别人客户端上旧实体已经没了，
        // 必须用新的 id 再广播一次，否则别人看你是「没模型」
        getModelInfoCap(newPlayer).ifPresent(cap -> {
            String modelId = cap.getModelId();
            Optional<S2CSetModelAndTexturePacket> msg = cap.createSyncMessage(newPlayer, false);
            YesSteveModel.LOGGER.info("[YSM-CLONE] player={} oldId={} newId={} oldModel={} newModel={} hasMsg={} defPresent={}",
                    newPlayer.getGameProfile().name(), oldPlayer.getId(), newPlayer.getId(),
                    oldModelInfoCap.map(ModelInfoCapability::getModelId).orElse("<none>"),
                    modelId == null ? "<null>" : modelId,
                    msg.isPresent(),
                    modelId != null && ServerModelManager.getModelDefinition(modelId).isPresent());
            if (modelId != null && !modelId.isEmpty()) {
                msg.ifPresent(NetworkHandler::sendToAll);
            }
        });
    }

    /**
     * 把一个玩家能看到的「其他所有在线玩家」的模型补发给他。
     * 用在两个时机：刚进服、死亡重生。这两个时刻客户端的世界实体是新建的，
     * 别人当初进服时广播的包早就过去了，他不会知道别人穿了什么 → 表现为「一边能看到、另一边是原版」。
     */
    public static void replayAllModelsTo(ServerPlayer target) {
        MinecraftServer server = target.level().getServer();
        if (server == null) {
            return;
        }
        for (ServerPlayer other : server.getPlayerList().getPlayers()) {
            if (other == target) {
                continue;
            }
            // 正在死亡/已被移除的玩家实体 id 已经作废，发给客户端只会绑到一个不存在的实体上
            if (other.isRemoved() || other.isDeadOrDying()) {
                YesSteveModel.LOGGER.info("[YSM-SYNC] replay skip dead player: to={} owner={} removed={} dying={}",
                        target.getGameProfile().name(), other.getGameProfile().name(), other.isRemoved(), other.isDeadOrDying());
                continue;
            }
            getModelInfoCap(other).ifPresent(otherCap -> {
                String modelId = otherCap.getModelId();
                if (modelId == null || modelId.isEmpty()) {
                    return;
                }
                otherCap.createSyncMessage(other, false).ifPresent(message -> {
                    NetworkHandler.sendToClientPlayer(message, target);
                    YesSteveModel.LOGGER.info("[YSM-SYNC] replay model: to={} owner={} model={}",
                            target.getGameProfile().name(), other.getGameProfile().name(), modelId);
                });
            });
        }
    }

    private static EventResult onEntityAdd(Entity entity, Level level) {        if (!YesSteveModel.isAvailable()) {
            return EventResult.pass();
        }
        if (entity instanceof ServerPlayer player) {
            getModelInfoCap(player).ifPresent(modelInfoCap -> {
                if (!NetworkHandler.isPlayerConnected(player) && !modelInfoCap.isMandatory()) {
                    modelInfoCap.markDirty();
                    return;
                }
                modelInfoCap.stopAnimation(player);
                Optional<S2CSetModelAndTexturePacket> optional = modelInfoCap.createSyncMessage(player, false);
                YesSteveModel.LOGGER.info("[YSM-ADD] player={} id={} model={} hasMessage={}",
                        player.getGameProfile().name(), player.getId(), modelInfoCap.getModelId(), optional.isPresent());
                // 广播：新玩家进服时让已在线的其他玩家也知道他穿了什么模型
                Consumer<? super S2CSetModelAndTexturePacket> consumer = NetworkHandler::sendToAll;
                Objects.requireNonNull(modelInfoCap);
                optional.ifPresentOrElse(consumer, modelInfoCap::markDirty);
            });
            getAuthModelsCap(player).ifPresent(authModelsCap -> {
                for (String modelId : ServerModelManager.getAuthModels()) {
                    authModelsCap.addModel(modelId);
                }
                NetworkHandler.sendToClientPlayer(new S2CSyncAuthModelsPacket(authModelsCap.getAuthModels()), player);
            });
            getStarModelsCap(player).ifPresent(starModelsCap -> NetworkHandler.sendToClientPlayer(new S2CSyncStarModelsPacket(starModelsCap.getStarModels()), player));
        }
        return EventResult.pass();
    }

    private static void onServerTick(MinecraftServer server) {
        if (!YesSteveModel.isAvailable()) {
            return;
        }
        List<ServerPlayer> players = server.getPlayerList().getPlayers();
        Boolean bool = ServerConfig.LOW_BANDWIDTH_USAGE.get();
        for (ServerPlayer serverPlayer : players) {
            getModelInfoCap(serverPlayer).ifPresent(cap -> {
                if (!NetworkHandler.isPlayerConnected(serverPlayer) && !cap.isMandatory()) {
                    if (serverPlayer.tickCount == 200 || serverPlayer.tickCount == 600 || serverPlayer.tickCount == 1800) {
                        NetworkHandler.sendToClientPlayer(new S2CVersionCheckPacket(), serverPlayer);
                    }
                    return;
                }
                if (cap.isDirty()) {
                    cap.getAnimSync().updateAndSync(serverPlayer, false, bool);
                    cap.createSyncMessage(serverPlayer, true).ifPresent(message -> {
                        cap.clearDirty();
                        NetworkHandler.sendToTrackingEntityAndSelf(message, serverPlayer);
                        if (serverPlayer.getVehicle() != null && serverPlayer.getVehicle().getFirstPassenger() == serverPlayer) {
                            syncVehicleModel(serverPlayer.getVehicle(), serverPlayer);
                        }
                    });
                } else {
                    cap.getAnimSync().updateAndSync(serverPlayer, true, bool);
                }
            });
        }
    }

    public static void syncProjectileModel(Projectile projectile, ServerPlayer serverPlayer) {
        ModelInfoCapability.get(serverPlayer).ifPresent(modelInfoCap -> {
            if (!NetworkHandler.isPlayerConnected(serverPlayer) && !modelInfoCap.isMandatory()) {
                return;
            }
            ProjectileModelCapability.get(projectile).ifPresent(projectileModelCap -> modelInfoCap.withMolangVars(object2FloatOpenHashMap -> {
                projectileModelCap.setModel(modelInfoCap.getModelId(), object2FloatOpenHashMap);
                NetworkHandler.sendToTrackingEntity(new S2CSyncProjectileModelPacket(projectile.getId(), projectileModelCap), projectile);
            }));
        });
    }

    public static void syncVehicleModel(Entity entity, ServerPlayer serverPlayer) {
        ModelInfoCapability.get(serverPlayer).ifPresent(modelInfoCap -> {
            if (!NetworkHandler.isPlayerConnected(serverPlayer) && !modelInfoCap.isMandatory()) {
                return;
            }
            VehicleModelCapability.get(entity).ifPresent(vehicleModelCap -> modelInfoCap.getMolangVars().ifPresent(object2FloatOpenHashMap -> {
                vehicleModelCap.setModel(modelInfoCap.getModelId(), object2FloatOpenHashMap);
                NetworkHandler.sendToTrackingEntity(new S2CSyncVehicleModelPacket(entity.getId(), vehicleModelCap), entity);
            }));
        });
    }

    public static Optional<ModelInfoCapability> getModelInfoCap(Player player) {
        return ModelInfoCapability.get(player);
    }

    public static Optional<AuthModelsCapability> getAuthModelsCap(Player player) {
        return AuthModelsCapability.get(player);
    }

    public static Optional<StarModelsCapability> getStarModelsCap(Player player) {
        return StarModelsCapability.get(player);
    }
}
