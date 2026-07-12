package com.feed.client;

import net.minecraft.network.chat.Component;

public class FeedApiException extends Exception {

    public final int status;

    public FeedApiException(int status, String message) {
        super(message);
        this.status = status;
    }

    public FeedApiException(Throwable cause) {
        super(cause);
        this.status = 0;
    }

    public boolean isRateLimited() {
        return status == 429;
    }

    public boolean isNetwork() {
        return status == 0;
    }

    public Component userMessage() {
        if (isRateLimited()) {
            if ("comment_rate_limited".equals(getMessage())) {
                return Component.translatable("feed.screen.post.rate_limited");
            }
            return Component.translatable("feed.screen.compose.rate_limited");
        }
        if (isNetwork()) {
            return Component.translatable("feed.screen.error.network");
        }
        String msg = getMessage();
        return msg == null || msg.isBlank()
                ? Component.translatable("feed.screen.error.unknown")
                : Component.literal(msg);
    }
}
