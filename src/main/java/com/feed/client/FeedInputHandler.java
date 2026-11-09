package com.feed.client;

import net.minecraft.client.Minecraft;

/** Drains queued keybind clicks with {@code while (consumeClick())}. */
public final class FeedInputHandler {

    public static void onKeyPress() {
        while (ModKeyMappings.OPEN_FEED.consumeClick()) {
            if (Minecraft.getInstance().screen == null) {
                Minecraft.getInstance().setScreen(new com.feed.client.screen.FeedScreen());
            }
        }
    }

    private FeedInputHandler() {
    }
}
