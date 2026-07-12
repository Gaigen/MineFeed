package com.feed.client;

import com.feed.Feed;
import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.InputEvent;

/** Drains queued keybind clicks with {@code while (consumeClick())}. */
@EventBusSubscriber(modid = Feed.MOD_ID, value = Dist.CLIENT)
public final class FeedInputHandler {

    @SubscribeEvent
    public static void onKeyPress(InputEvent.Key event) {
        while (ModKeyMappings.OPEN_FEED.consumeClick()) {
            if (Minecraft.getInstance().screen == null) {
                Minecraft.getInstance().setScreen(new com.feed.client.screen.FeedScreen());
            }
        }
    }

    private FeedInputHandler() {
    }
}
