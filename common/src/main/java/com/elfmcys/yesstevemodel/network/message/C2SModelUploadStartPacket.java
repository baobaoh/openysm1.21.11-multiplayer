package com.elfmcys.yesstevemodel.network.message;

import com.elfmcys.yesstevemodel.model.ModelUploadManager;
import com.elfmcys.yesstevemodel.network.NetworkHandler;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import rip.ysm.api.network.PacketContext;

public record C2SModelUploadStartPacket(String modelId, int totalBytes, String sha256) {

    public static void encode(C2SModelUploadStartPacket message, FriendlyByteBuf buf) {
        buf.writeUtf(message.modelId);
        buf.writeVarInt(message.totalBytes);
        buf.writeUtf(message.sha256);
    }

    public static C2SModelUploadStartPacket decode(FriendlyByteBuf buf) {
        return new C2SModelUploadStartPacket(buf.readUtf(), buf.readVarInt(), buf.readUtf());
    }

    public static void handle(C2SModelUploadStartPacket message, PacketContext ctx) {
        if (!ctx.isServerSide()) {
            return;
        }
        ctx.enqueueWork(() -> {
            ServerPlayer sender = ctx.getSender();
            if (sender == null) {
                return;
            }
            ModelUploadManager.StartResult result = ModelUploadManager.start(sender, message.modelId(), message.totalBytes(), message.sha256());
            NetworkHandler.sendToClientPlayer(new S2CModelUploadStartPacket(
                    result.uploadId(),
                    result.status(),
                    ModelUploadManager.chunkSize(),
                    ModelUploadManager.maxTotalBytes(),
                    ModelUploadManager.chunksPerTick(),
                    result.message()), sender);
        });
    }
}
