package com.feed.client;

import com.feed.Feed;
import com.feed.client.screen.ComposeScreen;
import com.feed.client.screen.FeedScreen;
import com.feed.client.screen.RetroButton;
import com.feed.data.PostType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.DeathScreen;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.ScreenEvent;

/**
 * Opens compose after respawn — a screen over DeathScreen blocks respawn.
 */
@EventBusSubscriber(modid = Feed.MOD_ID, value = Dist.CLIENT)
public final class DeathHook {

    private static String pending;

    private DeathHook() {
    }

    private static String deathMessage(DeathScreen screen, Minecraft mc) {
        try {
            var f = DeathScreen.class.getDeclaredField("causeOfDeath");
            f.setAccessible(true);
            Component cause = (Component) f.get(screen);
            if (cause != null) {
                return cause.getString();
            }
        } catch (Exception ignored) {
        }
        return mc.player.getCombatTracker().getDeathMessage().getString();
    }

    @SubscribeEvent
    public static void onScreenInit(ScreenEvent.Init.Post event) {
        if (!FeedConfig.deathHookEnabled() || !(event.getScreen() instanceof DeathScreen screen)) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) {
            return;
        }
        String message = deathMessage(screen, mc);
        event.addListener(new RetroButton(screen.width / 2 - 100, screen.height / 4 + 120, 200, 18,
                Component.translatable("feed.screen.death.post"), b -> {
            pending = message;
            b.setMessage(Component.translatable("feed.screen.death.queued"));
            b.active = false;
        }));
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        if (pending == null) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) {
            pending = null;
            return;
        }
        if (mc.screen == null && mc.player.isAlive()) {
            String text = pending;
            pending = null;
            mc.setScreen(new ComposeScreen(new FeedScreen(), PostType.RIP, text));
        }
    }
}
