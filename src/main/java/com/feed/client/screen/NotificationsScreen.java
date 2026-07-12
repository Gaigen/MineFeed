package com.feed.client.screen;

import com.feed.client.FeedApiClient;
import com.feed.client.NotificationPoller;
import com.feed.client.PostOpener;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

import java.util.List;

@OnlyIn(Dist.CLIENT)
public class NotificationsScreen extends Screen {

    private static final int PAD = 8;
    private static final int ROW_H = 36;
    private static final int TOP = 30;

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
        items = NotificationPoller.unreadList();
        scroll = 0;
    }

    @Override
    public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        g.fill(0, 0, this.width, this.height, RetroUi.BG);
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);

        String t = this.title.getString() + (RetroUi.blink() ? "_" : " ");
        g.drawCenteredString(this.font, t, this.width / 2, 11, RetroUi.FG);

        int cx = contentLeft();
        int cw = contentWidth();
        int bottom = this.height - 8;
        g.enableScissor(0, TOP, this.width, bottom);

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
                String thread = "\u2192 " + safe(RetroUi.flatten(n.postText()));
                g.drawString(this.font, clip(thread, cw - PAD * 2), cx + PAD, y + 5, RetroUi.ACCENT, false);
                String line = safe(RetroUi.flatten(n.author())) + ": " + safe(RetroUi.flatten(n.text()));
                g.drawString(this.font, clip(line, cw - PAD * 2), cx + PAD, y + 18, RetroUi.FG, false);
            }
            y += ROW_H + 6;
        }
        g.disableScissor();
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (super.mouseClicked(mouseX, mouseY, button)) {
            return true;
        }
        if (button != 0) {
            return false;
        }
        int cx = contentLeft();
        int cw = contentWidth();
        int y = TOP - scroll;
        for (FeedApiClient.Notif n : items) {
            if (mouseY >= y && mouseY < y + ROW_H && mouseX >= cx && mouseX < cx + cw) {
                PostOpener.open(this.minecraft, parent, n.postId());
                return true;
            }
            y += ROW_H + 6;
        }
        return false;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double dx, double dy) {
        if (super.mouseScrolled(mouseX, mouseY, dx, dy)) {
            return true;
        }
        scroll -= (int) (dy * 18);
        int max = Math.max(0, items.size() * (ROW_H + 6) - (this.height - TOP - 8));
        scroll = Math.max(0, Math.min(scroll, max));
        return true;
    }

    private String safe(String s) {
        return s == null || s.isEmpty() ? "?" : s;
    }

    private String clip(String s, int maxW) {
        if (this.font.width(s) <= maxW) {
            return s;
        }
        return this.font.plainSubstrByWidth(s, maxW - this.font.width("...")) + "...";
    }

    private int contentWidth() {
        return Math.min(360, this.width - 24);
    }

    private int contentLeft() {
        return (this.width - contentWidth()) / 2;
    }

    @Override
    public void onClose() {
        this.minecraft.setScreen(parent);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
