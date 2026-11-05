package com.feed.client;

import com.feed.client.screen.FeedScreen;
import com.feed.client.screen.PostScreen;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

public final class PostOpener {

    private PostOpener() {
    }

    /** Notification click: the post, or the community for join/application notifications. */
    public static void open(Minecraft mc, FeedScreen parent, FeedApiClient.Notif n) {
        if (n == null) {
            return;
        }
        if (n.postId() != null && !n.postId().isEmpty()) {
            open(mc, parent, n.postId());
            return;
        }
        NotificationPoller.markNotifRead(n.id());
        if (n.communityId() != null) {
            FeedScreen feed = parent != null ? parent : new FeedScreen();
            CommunityApi.mine().whenComplete((m, ex) -> mc.execute(() -> {
                feed.switchSpace(CommunityApi.cachedById(n.communityId()) != null ? n.communityId() : "global");
                if ("request".equals(n.kind())) {
                    CommunityApi.Community c = CommunityApi.cachedById(n.communityId());
                    mc.setScreen(c != null ? new com.feed.client.screen.CommunityScreen(feed, c) : feed);
                } else {
                    mc.setScreen(feed);
                }
            }));
        }
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
