package com.elfmcys.yesstevemodel.command.subcommands.client;

import com.elfmcys.yesstevemodel.client.upload.ModelUploadSession;
import com.elfmcys.yesstevemodel.util.YSMMessageFormatter;
import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import dev.architectury.event.events.client.ClientCommandRegistrationEvent;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * /openysm upload &lt;本地 .ysm 路径&gt; —— 把本地模型传到服务器。
 * 走的是和上传界面完全相同的链路（ModelUploadSession → C2S 包），
 * 方便在命令行里验证服务端收包/校验/落盘这一整套。
 */
public class UploadCommand {

    public static LiteralArgumentBuilder<ClientCommandRegistrationEvent.ClientCommandSourceStack> register() {
        RequiredArgumentBuilder<ClientCommandRegistrationEvent.ClientCommandSourceStack, String> path =
                RequiredArgumentBuilder.argument("path", StringArgumentType.greedyString());
        path.executes(UploadCommand::upload);
        return LiteralArgumentBuilder.<ClientCommandRegistrationEvent.ClientCommandSourceStack>literal("upload").then(path);
    }

    private static int upload(CommandContext<ClientCommandRegistrationEvent.ClientCommandSourceStack> context) {
        var player = Minecraft.getInstance().player;
        if (player == null) {
            return 0;
        }
        String raw = StringArgumentType.getString(context, "path").trim();
        if (raw.startsWith("\"") && raw.endsWith("\"") && raw.length() > 1) {
            raw = raw.substring(1, raw.length() - 1);
        }
        try {
            Path file = Path.of(raw);
            byte[] data = Files.readAllBytes(file);
            String fileName = file.getFileName().toString();
            String error = ModelUploadSession.start(fileName, data);
            if (error == null) {
                player.displayClientMessage(YSMMessageFormatter.withPrefix(
                        Component.literal("开始上传 " + fileName + " (" + ModelUploadSession.formatBytes(data.length) + ")")), false);
            } else {
                player.displayClientMessage(YSMMessageFormatter.withPrefix(Component.literal(error)), false);
            }
        } catch (Exception e) {
            player.displayClientMessage(YSMMessageFormatter.withPrefix(
                    Component.literal("读取文件失败: " + e.getMessage())), false);
        }
        return Command.SINGLE_SUCCESS;
    }
}
