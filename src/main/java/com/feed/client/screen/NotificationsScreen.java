package com.feed.client.screen;

import com.feed.client.FeedApiClient;
import com.feed.client.FeedTime;
import com.feed.client.FeedUtil;
import com.feed.client.NotificationOrder;
import com.feed.client.NotificationPoller;
import com.feed.client.PostOpener;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

import java.util.ArrayList;
import java.util.List;

/** Unread inbox: opening the screen does not mark everything read. */
@OnlyIn(Dist.CLIENT)
public class NotificationsScreen extends RetroScreen {
    private static final int PAD = 8;
    private static final int ROW_H = 52;
    private static final int TOP = 48;
    private final FeedScreen parent;
    private List<FeedApiClient.Notif> items = List.of();
    private int scroll;

    public NotificationsScreen(FeedScreen parent) {
        super(Component.translatable("feed.screen.notifications.title"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        addRenderableWidget(new RetroButton(8, 6, 56, 18,
                Component.translatable("feed.screen.post.back"), b -> onClose()));
        int buttonWidth = Math.min(130, Math.max(72, this.width / 3));
        addRenderableWidget(new RetroButton(this.width - buttonWidth - 8, 6, buttonWidth, 18,
                Component.translatable("feed.screen.notifications.clear"), b -> {
                    NotificationPoller.markAllSeen();
                    refreshItems(true);
                }));
        refreshItems(true);
    }

    private void refreshItems(boolean resetScroll) {
        List<FeedApiClient.Notif> latest = new ArrayList<>(NotificationPoller.unreadList());
        latest.sort((a, b) -> NotificationOrder.compare(b.id(), a.id()));
        if (!latest.equals(items)) items = latest;
        if (resetScroll) scroll = 0;
        int max = Math.max(0, items.size() * (ROW_H + 6) - (this.height - TOP - 8));
        scroll = Math.max(0, Math.min(scroll, max));
    }

    @Override
    public void tick() {
        super.tick();
        refreshItems(false);
    }

    @Override
    public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        g.fill(0, 0, this.width, this.height, RetroUi.BG);
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);
        int cx = contentLeft(), cw = contentWidth(), bottom = this.height - 8;
        String count = Component.translatable("feed.screen.notifications.count", items.size()).getString();
        int titleWidth = Math.max(0, cw - this.font.width(count) - 10);
        g.drawString(this.font, FeedUtil.clip(this.font, this.title.getString(), titleWidth), cx, 30, RetroUi.FG, false);
        g.drawString(this.font, count, cx + Math.max(0, cw - this.font.width(count)), 30, RetroUi.GRAY, false);
        g.enableScissor(0, TOP, this.width, Math.max(TOP, bottom));
        if (items.isEmpty()) {
            g.drawCenteredString(this.font, Component.translatable("feed.screen.notifications.empty"),
                    this.width / 2, TOP + 20, RetroUi.GRAY);
        }
        int y = TOP - scroll;
        for (FeedApiClient.Notif n : items) {
            if (y + ROW_H >= TOP && y <= bottom) {
                boolean hover = mouseX >= cx && mouseX < cx + cw
                        && mouseY >= Math.max(y, TOP) && mouseY < Math.min(y + ROW_H, bottom);
                RetroUi.drawBox(g, cx, y, cw, ROW_H, hover);
                String time = FeedTime.ago(n.createdAt());
                int timeWidth = this.font.width(time);
                String author = safe(RetroUi.flatten(n.author()));
                g.drawString(this.font, FeedUtil.clip(this.font, author, Math.max(5, cw - PAD * 3 - timeWidth)),
                        cx + PAD, y + 5, RetroUi.ACCENT, false);
                g.drawString(this.font, time, cx + cw - PAD - timeWidth, y + 5, RetroUi.GRAY, false);
                String thread = Component.translatable("feed.screen.notifications.post", safe(RetroUi.flatten(n.postText()))).getString();
                g.drawString(this.font, FeedUtil.clip(this.font, thread, cw - PAD * 2), cx + PAD, y + 18, RetroUi.GRAY, false);
                g.drawString(this.font, FeedUtil.clip(this.font, safe(RetroUi.flatten(n.text())), cw - PAD * 2),
                        cx + PAD, y + 31, RetroUi.FG, false);
            }
            y += ROW_H + 6;
        }
        g.disableScissor();
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (super.mouseClicked(mouseX, mouseY, button)) return true;
        if (button != 0 || mouseY < TOP || mouseY >= this.height - 8) return false;
        int cx = contentLeft(), cw = contentWidth(), y = TOP - scroll;
        for (FeedApiClient.Notif n : items) {
            if (mouseY >= y && mouseY < y + ROW_H && mouseX >= cx && mouseX < cx + cw) {
                PostOpener.open(this.minecraft, parent, n);
                return true;
            }
            y += ROW_H + 6;
        }
        return false;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double dx, double dy) {
        if (super.mouseScrolled(mouseX, mouseY, dx, dy)) return true;
        scroll -= (int) (dy * 18);
        int max = Math.max(0, items.size() * (ROW_H + 6) - (this.height - TOP - 8));
        scroll = Math.max(0, Math.min(scroll, max));
        return true;
    }

    private String safe(String s) { return s == null || s.isEmpty() ? "?" : s; }
    @Override public void onClose() { this.minecraft.setScreen(parent); }
}
