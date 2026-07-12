package com.feed.client;

import com.feed.client.screen.FeedScreen;
import com.feed.client.screen.PostScreen;
import net.minecraft.client.Minecraft;

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
                        return;
                    }
                    NotificationPoller.markRead(postId);
                    FeedScreen feed = parent != null ? parent : new FeedScreen();
                    mc.setScreen(new PostScreen(feed, post));
                }));
    }
}
