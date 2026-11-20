package com.elfmcys.yesstevemodel.network.message;

import com.elfmcys.yesstevemodel.capability.PlayerCapability;
import com.elfmcys.yesstevemodel.event.EntityJoinCallbackEvent;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.entity.Entity;
import rip.ysm.api.network.PacketContext;

import java.util.UUID;

public class S2CSetModelAndTexturePacket {

    private final int entityId;

    /**
     * 玩家 UUID。客户端只用 entityId 匹配实体，而复活后客户端与服役端的实体 id 可能不一致
     * （实测：服务端 newId=470，客户端仍把它当作 163），因此额外带上 UUID 做兜底匹配。
     */
    private final UUID ownerUuid;

    private final String modelId;

    private final String textureId;

    private final boolean disabled;

    private final S2CSyncPlayerStatePacket entityModelSync;

    public S2CSetModelAndTexturePacket(int entityId, UUID ownerUuid, String modelId, String textureId, boolean disabled, S2CSyncPlayerStatePacket playerState) {
        this.entityId = entityId;
        this.ownerUuid = ownerUuid;
        this.modelId = modelId;
        this.textureId = textureId;
        this.entityModelSync = playerState;
        this.disabled = disabled;
    }

    public static void encode(S2CSetModelAndTexturePacket other, FriendlyByteBuf friendlyByteBuf) {
        friendlyByteBuf.writeVarInt(other.entityId);
        friendlyByteBuf.writeBoolean(other.ownerUuid != null);
        if (other.ownerUuid != null) {
            friendlyByteBuf.writeUUID(other.ownerUuid);
        }
        friendlyByteBuf.writeUtf(other.modelId);
        friendlyByteBuf.writeUtf(other.textureId);
        friendlyByteBuf.writeBoolean(other.disabled);
        S2CSyncPlayerStatePacket.encode(other.entityModelSync, friendlyByteBuf);
    }

    public static S2CSetModelAndTexturePacket decode(FriendlyByteBuf friendlyByteBuf) {
        int entityId = friendlyByteBuf.readVarInt();
        UUID ownerUuid = friendlyByteBuf.readBoolean() ? friendlyByteBuf.readUUID() : null;
        String modelId = friendlyByteBuf.readUtf();
        String textureId = friendlyByteBuf.readUtf();
        boolean disabled = friendlyByteBuf.readBoolean();
        return new S2CSetModelAndTexturePacket(entityId, ownerUuid, modelId, textureId, disabled, S2CSyncPlayerStatePacket.decode(friendlyByteBuf));
    }

    public static void handle(S2CSetModelAndTexturePacket other, PacketContext ctx) {
        if (ctx.isClientSide()) {
            com.elfmcys.yesstevemodel.YesSteveModel.LOGGER.info("[YSM-RECV] entity={} uuid={} model={} texture={} disabled={}",
                    other.entityId, other.ownerUuid, other.modelId, other.textureId, other.disabled);
            EntityJoinCallbackEvent.addCallback(other.entityId, other.ownerUuid, entity -> applyOnClient(entity, other));
        }
    }

    @Environment(EnvType.CLIENT)
    public static void applyOnClient(Entity entity, S2CSetModelAndTexturePacket other) {
        PlayerCapability.get(entity).ifPresentOrElse(cap -> {
            com.elfmcys.yesstevemodel.YesSteveModel.LOGGER.info("[YSM-APPLY] entity={} name={} model={} texture={}",
                    entity.getId(), entity.getName().getString(), other.modelId, other.textureId);
            cap.initModelWithTexture(other.modelId, other.textureId);
            cap.setForceDisabled(other.disabled);
            S2CSyncPlayerStatePacket.handleCapability(entity, other.entityModelSync);
        }, () -> com.elfmcys.yesstevemodel.YesSteveModel.LOGGER.info("[YSM-APPLY] entity={} has no PlayerCapability", entity.getId()));
    }
}
