package com.elfmcys.yesstevemodel.network.message;

import com.elfmcys.yesstevemodel.model.ModelUploadManager;
import com.elfmcys.yesstevemodel.network.NetworkHandler;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import rip.ysm.api.network.PacketContext;

public record C2SModelUploadFinishPacket(long uploadId) {

    public static void encode(C2SModelUploadFinishPacket message, FriendlyByteBuf buf) {
        buf.writeVarLong(message.uploadId);
    }

    public static C2SModelUploadFinishPacket decode(FriendlyByteBuf buf) {
        return new C2SModelUploadFinishPacket(buf.readVarLong());
    }

    public static void handle(C2SModelUploadFinishPacket message, PacketContext ctx) {
        if (!ctx.isServerSide()) {
            return;
        }
        ctx.enqueueWork(() -> {
            ServerPlayer sender = ctx.getSender();
            if (sender == null) {
                return;
            }
            ModelUploadManager.FinishResult result = ModelUploadManager.finish(sender, message.uploadId());
            NetworkHandler.sendToClientPlayer(new S2CModelUploadResultPacket(
                    message.uploadId(),
                    result.status(),
                    result.modelId(),
                    0L,
                    0L,
                    result.message() == null ? "" : result.message()), sender);
        });
    }
}
