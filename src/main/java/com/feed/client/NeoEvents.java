package com.feed.client;

import com.feed.Feed;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.commands.Commands;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;
import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.client.event.RenderGuiEvent;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.neoforged.neoforge.event.entity.player.AdvancementEvent;

@EventBusSubscriber(modid = Feed.MOD_ID, value = Dist.CLIENT)
public final class NeoEvents {
    @SubscribeEvent public static void commands(RegisterClientCommandsEvent e) {
        e.getDispatcher().register(Commands.literal("minefeed_local")
            .executes(ctx -> FeedChatCommand.usage())
            .then(Commands.argument("action", StringArgumentType.greedyString())
                .executes(ctx -> FeedChatCommand.run(StringArgumentType.getString(ctx, "action")))));
    }
    @SubscribeEvent public static void key(InputEvent.Key e) { FeedInputHandler.onKeyPress(); }
    @SubscribeEvent public static void login(ClientPlayerNetworkEvent.LoggingIn e) { NotificationPoller.onLogin(); }
    @SubscribeEvent public static void logout(ClientPlayerNetworkEvent.LoggingOut e) { NotificationPoller.onLogout(); }
    @SubscribeEvent public static void tick(ClientTickEvent.Post e) { NotificationPoller.onTick(); DeathHook.onClientTick(); }
    @SubscribeEvent public static void hud(RenderGuiEvent.Post e) { NotificationPoller.onHud(e.getGuiGraphics()); }
    @SubscribeEvent public static void mouse(InputEvent.MouseButton.Pre e) {
        if (NotificationPoller.onMouseClick(e.getButton(), e.getAction())) e.setCanceled(true);
    }
    @SubscribeEvent public static void screen(ScreenEvent.Init.Post e) {
        InventoryFeedButton.onScreenInit(e.getScreen(), e::addListener);
        DeathHook.onScreenInit(e.getScreen(), e::addListener);
    }
    @SubscribeEvent public static void advancement(AdvancementEvent.AdvancementEarnEvent e) {
        if (e.getEntity() == Minecraft.getInstance().player) AdvancementHook.onAdvancement(e.getAdvancement());
    }
    private NeoEvents() {}
}
