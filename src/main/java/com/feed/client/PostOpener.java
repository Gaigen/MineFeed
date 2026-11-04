package com.feed.client;

import com.feed.client.screen.FeedScreen;
import com.feed.client.screen.PostScreen;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

public final class PostOpener {

    private PostOpener() {
    }

    public static void open(Minecraft mc, FeedScreen parent, String postId) {
        if (postId == null || postId.isEmpty()) {
            return;
        }
        FeedApiClient.INSTANCE.fetchPost(postId).whenComplete((post, ex) ->
                mc.execute(() -> {
                    if (ex != null || post == null) {
                        if (mc.player != null) {
                            Component detail = ex == null ? Component.translatable("feed.screen.error.unknown")
                                    : FeedApiClient.unwrap(ex).userMessage();
                            mc.player.displayClientMessage(Component.translatable("feed.chat.open.failed", detail)
                                    .withStyle(ChatFormatting.RED), false);
                        }
                        return;
                    }
                    NotificationPoller.markRead(postId);
                    FeedScreen feed = parent != null ? parent : new FeedScreen();
                    mc.setScreen(new PostScreen(feed, post));
                }));
    }
}
