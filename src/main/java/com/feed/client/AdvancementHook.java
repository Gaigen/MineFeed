package com.feed.client;

import com.feed.client.screen.ComposeScreen;
import com.feed.client.screen.FeedScreen;
import com.feed.data.PostType;
import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

/** Auto-compose GG post when the local player earns an advancement. */
public final class AdvancementHook {

    private AdvancementHook() {
    }

    public static void onAdvancement(AdvancementHolder adv) {
        if (!FeedConfig.advancementGgEnabled()) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) {
            return;
        }
        if (adv == null) {
            return;
        }
        Component title = adv.value().display().map(d -> d.getTitle()).orElse(null);
        if (title == null) {
            return;
        }
        String text = title.getString();
        if (text.isBlank()) {
            return;
        }
        String composed = MessageTemplate.render(FeedConfig.advancementTemplate(), java.util.Map.of(
                "advancement", text, "player", mc.getUser().getName()));
        mc.execute(() -> mc.setScreen(new ComposeScreen(new FeedScreen(), PostType.GG, composed)));
    }
}
