package com.feed.client;

import com.feed.data.Post;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.network.chat.Component;

/** Small actions shared by the feed and the post screen: poll votes, copying coordinates. */
public final class PostActions {

    private PostActions() {
    }

    /** Click on a poll option: vote, switch the vote, or take it back when it is already mine. */
    public static void vote(Post post, int option, Runnable onDone) {
        int choice = option == post.pollMy ? -1 : option;
        FeedApiClient.INSTANCE.vote(post, choice).whenComplete((p, ex) -> Minecraft.getInstance().execute(() -> {
            if (ex != null) {
                toast(Component.translatable("feed.poll.title"), FeedApiClient.unwrap(ex).userMessage());
                return;
            }
            if (onDone != null) {
                onDone.run();
            }
        }));
    }

    /** "x y z [nether|end]" from a post: copies "x y z" and says how far it is when in the same dimension. */
    public static void copyCoords(String raw) {
        String[] p = raw.trim().split(" ");
        if (p.length < 3) {
            return;
        }
        String xyz = p[0] + " " + p[1] + " " + p[2];
        Minecraft mc = Minecraft.getInstance();
        mc.keyboardHandler.setClipboard(xyz);
        String dim = p.length > 3 ? ("nether".equals(p[3]) ? "minecraft:the_nether" : "minecraft:the_end") : "minecraft:overworld";
        Component msg = Component.literal(xyz);
        if (mc.player != null && mc.level != null && mc.level.dimension().location().toString().equals(dim)) {
            try {
                double dx = Double.parseDouble(p[0]) - mc.player.getX();
                double dz = Double.parseDouble(p[2]) - mc.player.getZ();
                long dist = Math.round(Math.sqrt(dx * dx + dz * dz));
                msg = Component.translatable("feed.coords.distance", xyz, dist);
            } catch (NumberFormatException ignored) {
                // keep the plain text
            }
        }
        toast(Component.translatable("feed.coords.copied"), msg);
    }

    private static void toast(Component title, Component msg) {
        Minecraft mc = Minecraft.getInstance();
        SystemToast.addOrUpdate(mc.getToasts(), SystemToast.SystemToastId.PERIODIC_NOTIFICATION, title, msg);
    }
}
