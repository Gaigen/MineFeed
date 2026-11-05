package com.feed.client;

import com.feed.Feed;
import com.feed.client.screen.FeedScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ScreenEvent;

/**
 * Development only: with -Dminefeed.preview the feed opens straight from the title screen,
 * so the UI can be checked without loading a world.
 */
@EventBusSubscriber(modid = Feed.MOD_ID, value = Dist.CLIENT)
public final class DevPreview {

    private static boolean opened;

    private DevPreview() {
    }

    @SubscribeEvent
    public static void onScreenInit(ScreenEvent.Init.Post event) {
        if (opened || System.getProperty("minefeed.preview") == null || !(event.getScreen() instanceof TitleScreen)) {
            return;
        }
        opened = true;
        event.getScreen().getMinecraft().tell(() -> event.getScreen().getMinecraft().setScreen(new FeedScreen()));
    }
}
