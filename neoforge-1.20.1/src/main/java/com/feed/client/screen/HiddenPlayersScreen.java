package com.feed.client.screen;

import com.feed.client.FeedUtil;
import com.feed.client.HiddenPlayers;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** List of players I hid, with "Show" for each. */
public class HiddenPlayersScreen extends ModalScreen {

    private static final int ROW = 18;
    private static final int MAX_ROWS = 8;
    private int scroll;

    public HiddenPlayersScreen(FeedScreen parent) {
        super(Component.translatable("feed.hide.list_title"), parent);
    }

    @Override
    protected void init() {
        int rows = Math.max(1, Math.min(MAX_ROWS, HiddenPlayers.count()));
        layout(300, PAD + 16 + 14 + rows * ROW + PAD);
    }

    @Override
    protected void renderContent(GuiGraphics g, int mouseX, int mouseY) {
        int x = innerX();
        int w = innerW();
        int y = bodyTop();
        AccountUi.drawWrapped(g, this.font, tr("feed.hide.list_hint"), x, y, w, RetroUi.GRAY);
        y += 14;
        List<Map.Entry<String, String>> list = new ArrayList<>(HiddenPlayers.all().entrySet());
        if (list.isEmpty()) {
            g.drawString(this.font, tr("feed.hide.none"), x, y + 4, RetroUi.GRAY, false);
            return;
        }
        scroll = Math.max(0, Math.min(scroll, list.size() - MAX_ROWS));
        for (int i = scroll; i < list.size() && i < scroll + MAX_ROWS; i++) {
            var e = list.get(i);
            String id = e.getKey();
            String show = tr("feed.hide.unhide");
            int bw = buttonWidth(show);
            RetroUi.drawAvatar(g, this.font, com.feed.client.FeedApiClient.avatarKey(id, HiddenPlayers.avatar(id)), e.getValue(), x, y + 1, 12);
            boolean nameHover = mouseX >= x && mouseX < x + w - bw - 6 && mouseY >= y && mouseY < y + ROW - 2;
            g.drawString(this.font, FeedUtil.clip(this.font, e.getValue(), w - bw - 24), x + 16, y + 3,
                    nameHover ? RetroUi.ACCENT : RetroUi.FG, false);
            hit(x, y, w - bw - 6, ROW - 2, () -> {
                parent.openProfile(id);
                this.minecraft.setScreen(parent);
            });
            button(g, show, x + w - bw, y, bw, 14, RetroUi.FG, false, mouseX, mouseY, () -> {
                HiddenPlayers.unhide(id);
                rebuildWidgets();
            });
            y += ROW;
        }
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double dy) {
        scroll -= (int) Math.signum(dy);
        return true;
    }
}
