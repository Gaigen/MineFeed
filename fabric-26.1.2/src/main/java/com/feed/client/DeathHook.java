package com.feed.client;

import com.feed.client.screen.ComposeScreen;
import com.feed.client.screen.FeedScreen;
import com.feed.client.screen.RetroButton;
import com.feed.data.PostType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.DeathScreen;
import net.minecraft.network.chat.Component;

/**
 * Opens compose after respawn — a screen over DeathScreen blocks respawn.
 */
public final class DeathHook {

    private static String pending;

    private DeathHook() {
    }

    private static String deathMessage(DeathScreen screen, Minecraft mc) {
        Component cause = ((com.feed.mixin.DeathScreenAccessor) screen).feed$getCauseOfDeath();
        if (cause != null) return cause.getString();
        return mc.player.getCombatTracker().getDeathMessage().getString();
    }

    public static void onScreenInit(net.minecraft.client.gui.screens.Screen current, java.util.function.Consumer<net.minecraft.client.gui.components.events.GuiEventListener> addListener) {
        if (!FeedConfig.deathHookEnabled() || !(current instanceof DeathScreen screen)) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) {
            return;
        }
        var pos = mc.player.blockPosition();
        String coords = mc.level == null ? "" : com.feed.client.screen.TagText.coords(pos.getX(), pos.getY(), pos.getZ(),
                mc.level.dimension().identifier().toString());
        String message = MessageTemplate.render(FeedConfig.deathTemplate(), java.util.Map.of(
                "message", deathMessage(screen, mc), "player", mc.getUser().getName(), "coords", coords)).strip();
        addListener.accept(new RetroButton(screen.width / 2 - 100, screen.height / 4 + 120, 200, 18,
                Component.translatable("feed.screen.death.post"), b -> {
            pending = message;
            b.setMessage(Component.translatable("feed.screen.death.queued"));
            b.active = false;
        }));
    }

    public static void onClientTick() {
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
