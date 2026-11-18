package com.elfmcys.yesstevemodel.command;

import com.elfmcys.yesstevemodel.command.subcommands.client.CacheCommand;
import com.elfmcys.yesstevemodel.command.subcommands.client.UploadCommand;
import com.elfmcys.yesstevemodel.command.subcommands.client.UploadGuiCommand;
import com.elfmcys.yesstevemodel.util.YSMMessageFormatter;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import dev.architectury.event.events.client.ClientCommandRegistrationEvent;


public class OpenYSMClientCommand {

    public static void registerClientCommands(CommandDispatcher<ClientCommandRegistrationEvent.ClientCommandSourceStack> commandDispatcher) {
        LiteralArgumentBuilder<ClientCommandRegistrationEvent.ClientCommandSourceStack> root = LiteralArgumentBuilder.<ClientCommandRegistrationEvent.ClientCommandSourceStack>literal("openysm")
                .requires(source -> true);

        root.then(CacheCommand.register());
        root.then(UploadCommand.register());
        root.then(UploadGuiCommand.register());

        commandDispatcher.register(root);
    }
}