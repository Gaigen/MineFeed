package com.feed.client;

import com.feed.Feed;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;

/** Client-only command. A greedy argument ensures malformed MineFeed commands stay local too. */
@EventBusSubscriber(modid = Feed.MOD_ID, value = Dist.CLIENT)
public final class FeedChatCommand {
    private FeedChatCommand() {}

    @SubscribeEvent
    public static void register(RegisterClientCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("minefeed_local")
                .executes(ctx -> usage())
                .then(Commands.argument("action", StringArgumentType.greedyString())
                        .executes(ctx -> run(StringArgumentType.getString(ctx, "action")))));
    }

    private static int usage() {
        show(Component.translatable("feed.chat.action.usage").withStyle(ChatFormatting.RED));
        return 0;
    }

    private static int run(String input) {
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
        if (mc.player != null) mc.player.displayClientMessage(message, false);
    }
}
