package com.feed;
import com.feed.client.*;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.minecraft.client.gui.components.AbstractWidget;

public final class FabricClient implements ClientModInitializer {
    @Override public void onInitializeClient() {
        FeedConfig.load();
        KeyMappingHelper.registerKeyMapping(ModKeyMappings.OPEN_FEED);
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            FeedInputHandler.onKeyPress();
            NotificationPoller.onTick();
            DeathHook.onClientTick();
        });
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> NotificationPoller.onLogin());
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> NotificationPoller.onLogout());
        HudElementRegistry.addLast(net.minecraft.resources.Identifier.fromNamespaceAndPath("feed", "notifications"), (graphics, delta) -> NotificationPoller.onHud(graphics));
        ScreenEvents.AFTER_INIT.register((client, screen, width, height) -> {
            java.util.function.Consumer<net.minecraft.client.gui.components.events.GuiEventListener> add = widget -> Screens.getWidgets(screen).add((AbstractWidget) widget);
            InventoryFeedButton.onScreenInit(screen, add);
            DeathHook.onScreenInit(screen, add);
        });
        net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> dispatcher.register(
            net.fabricmc.fabric.api.client.command.v2.ClientCommands.literal("minefeed_local")
                .executes(ctx -> FeedChatCommand.usage())
                .then(net.fabricmc.fabric.api.client.command.v2.ClientCommands.argument("action", com.mojang.brigadier.arguments.StringArgumentType.greedyString())
                    .executes(ctx -> FeedChatCommand.run(com.mojang.brigadier.arguments.StringArgumentType.getString(ctx, "action"))))));
    }
}
