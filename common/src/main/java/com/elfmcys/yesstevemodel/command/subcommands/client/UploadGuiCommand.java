package com.elfmcys.yesstevemodel.command.subcommands.client;

import com.elfmcys.yesstevemodel.client.gui.ModelUploadScreen;
import com.mojang.brigadier.Command;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import dev.architectury.event.events.client.ClientCommandRegistrationEvent;
import net.minecraft.client.Minecraft;

/**
 * /openysm uploadgui —— 打开模型上传界面（拖拽 .ysm 到窗口即可上传）。
 * 不方便按 Y 进模型界面时，可以直接用这条命令打开。
 */
public class UploadGuiCommand {

    public static LiteralArgumentBuilder<ClientCommandRegistrationEvent.ClientCommandSourceStack> register() {
        return LiteralArgumentBuilder.<ClientCommandRegistrationEvent.ClientCommandSourceStack>literal("uploadgui")
                .executes(context -> {
                    Minecraft.getInstance().setScreen(new ModelUploadScreen(null));
                    return Command.SINGLE_SUCCESS;
                });
    }
}
