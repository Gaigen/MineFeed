package com.feed.client;

import com.feed.data.Post;
import net.minecraft.client.Minecraft;

import java.util.function.Consumer;

public final class ReactionHelper {

    private ReactionHelper() {
    }

    public static void toggle(Post post, String key, Consumer<FeedApiClient.ReactResult> onSuccess) {
        FeedApiClient.INSTANCE.toggleReaction(post, key)
                .whenComplete((r, ex) -> Minecraft.getInstance().execute(() -> {
                    if (ex != null || r == null) {
                        return;
                    }
                    post.reactions.putAll(r.counts());
                    post.myReaction = r.active();
                    if (onSuccess != null) {
                        onSuccess.accept(r);
                    }
                }));
    }
}
