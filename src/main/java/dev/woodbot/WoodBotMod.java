package dev.woodbot;

import com.mojang.brigadier.Command;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;

public class WoodBotMod implements ClientModInitializer {
    public static final WoodBot BOT = new WoodBot();

    @Override
    public void onInitializeClient() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, buildContext) ->
                dispatcher.register(ClientCommands.literal("woodbot")
                        .then(ClientCommands.literal("start").executes(ctx -> {
                            BOT.start(Minecraft.getInstance());
                            return Command.SINGLE_SUCCESS;
                        }))
                        .then(ClientCommands.literal("stop").executes(ctx -> {
                            BOT.stopByUser(Minecraft.getInstance());
                            return Command.SINGLE_SUCCESS;
                        }))));

        ClientTickEvents.END_CLIENT_TICK.register(BOT::tick);
    }
}
