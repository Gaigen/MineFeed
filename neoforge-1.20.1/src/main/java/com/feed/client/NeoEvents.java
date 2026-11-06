package com.feed.client;

import com.feed.Feed;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.commands.Commands;
import net.minecraftforge.client.event.RegisterClientCommandsEvent;
import net.minecraft.client.Minecraft;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.client.event.InputEvent;
import net.minecraftforge.client.event.RenderGuiEvent;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.event.entity.player.AdvancementEvent;

@Mod.EventBusSubscriber(modid = Feed.MOD_ID, value = Dist.CLIENT)
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
    @SubscribeEvent public static void tick(TickEvent.ClientTickEvent e) { if (e.phase != TickEvent.Phase.END) return; NotificationPoller.onTick(); DeathHook.onClientTick(); }
    @SubscribeEvent public static void hud(RenderGuiEvent.Post e) { NotificationPoller.onHud(e.getGuiGraphics()); }
    @SubscribeEvent public static void mouse(InputEvent.MouseButton.Pre e) {
        if (NotificationPoller.onMouseClick(e.getButton(), e.getAction())) e.setCanceled(true);
    }
    @SubscribeEvent public static void screen(ScreenEvent.Init.Post e) {
        InventoryFeedButton.onScreenInit(e.getScreen(), e::addListener);
        DeathHook.onScreenInit(e.getScreen(), e::addListener);
    }
    private NeoEvents() {}
}
