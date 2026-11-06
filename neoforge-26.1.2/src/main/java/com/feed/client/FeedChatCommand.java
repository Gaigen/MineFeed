package com.feed.client;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

/** Client-only command. A greedy argument ensures malformed MineFeed commands stay local too. */
public final class FeedChatCommand {
    private FeedChatCommand() {}

    public static int usage() {
        show(Component.translatable("feed.chat.action.usage").withStyle(ChatFormatting.RED));
        return 0;
    }

    public static int run(String input) {
        FeedChatActions.Action action = FeedChatActions.parse(input);
        if (action == null) return usage();
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return 0;
        if (action.kind().equals("open")) {
            PostOpener.open(mc, null, action.postId());
            return 1;
        }
        FeedApiClient.INSTANCE.createComment(action.postId(), action.text()).whenComplete((comment, error) ->
                mc.execute(() -> {
                    if (mc.player == null) return;
                    if (error != null || comment == null) {
                        Component detail = error == null ? Component.translatable("feed.screen.error.unknown")
                                : FeedApiClient.unwrap(error).userMessage();
                        show(Component.translatable("feed.chat.reply.failed", detail).withStyle(ChatFormatting.RED));
                        return;
                    }
                    NotificationPoller.markRead(action.postId());
                    show(Component.translatable("feed.chat.reply.sent").withStyle(ChatFormatting.GREEN));
                }));
        return 1;
    }

    private static void show(Component message) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) mc.gui.getChat().addClientSystemMessage(message);
    }
}
