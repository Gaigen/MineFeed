package com.feed.client.screen;

import com.feed.client.ModerationApi;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;

import java.util.function.Consumer;

/** Server ban: reason, length (1 / 7 / 30 days / forever), optionally delete the reported message. */
public class BanScreen extends ModalScreen {

    private static final int LABEL_W = 70;
    private static final int ROW = 20;

    private final String userId;
    private final String nick;
    private final String postId;
    private final String commentId;
    private final Consumer<Long> onBanned;
    private EditBox reasonBox;
    private String reason = "";
    private int days = 7;
    private boolean deleteContent = true;
    private boolean offline;

    /** postId/commentId: the message the ban is for (null from a profile). onBanned gets the end time or null = forever. */
    public BanScreen(FeedScreen home, String userId, String nick, String postId, String commentId, Consumer<Long> onBanned) {
        super(Component.translatable("feed.ban.title", nick == null ? "?" : nick), home);
        this.userId = userId;
        this.nick = nick == null ? "?" : nick;
        this.postId = postId;
        this.commentId = commentId;
        this.onBanned = onBanned;
    }

    /** Dev preview: no request, just report success. */
    public BanScreen offline() {
        offline = true;
        return this;
    }

    @Override
    protected void init() {
        layout(340, postId != null ? 150 : 134);
        int y = bodyTop() + 14;
        reasonBox = addField(AccountUi.field(this.font, innerX() + LABEL_W, y, innerW() - LABEL_W, 200,
                Component.translatable("feed.ban.reason_hint")));
        reasonBox.setValue(reason);
        reasonBox.setResponder(s -> reason = s);
        setInitialFocus(reasonBox);
    }

    @Override
    protected void renderContent(GuiGraphics g, int mouseX, int mouseY) {
        int x = innerX();
        int fx = x + LABEL_W;
        int y = bodyTop();
        g.drawString(this.font, tr("feed.ban.hint"), x, y, RetroUi.GRAY, false);
        y += 14;
        g.drawString(this.font, tr("feed.ban.reason"), x, y + 4, RetroUi.GRAY, false);
        y += ROW + 4;
        g.drawString(this.font, tr("feed.ban.length"), x, y + 2, RetroUi.GRAY, false);
        int cx = fx;
        for (int d : ModerationApi.BAN_DAYS) {
            String label = d == 0 ? tr("feed.code.forever") : tr("feed.code.days." + d);
            cx += chip(g, label, cx, y, RetroUi.ERROR, d == days, () -> days = d) + 4;
        }
        y += ROW - 2;
        if (postId != null) {
            String label = tr(commentId != null ? "feed.ban.delete_comment" : "feed.ban.delete_post");
            boolean hover = mouseX >= fx && mouseX < fx + 14 + this.font.width(label) && mouseY >= y - 1 && mouseY < y + 10;
            g.fill(fx, y, fx + 9, y + 9, RetroUi.BG);
            g.renderOutline(fx, y, 9, 9, hover ? RetroUi.FG : RetroUi.GRAY);
            if (deleteContent) {
                PixelIcon.draw(g, "check", fx + 1, y + 1, RetroUi.ACCENT);
            }
            g.drawString(this.font, label, fx + 14, y + 1, hover ? RetroUi.STRONG : RetroUi.FG, false);
            hit(fx, y - 1, 14 + this.font.width(label), 11, () -> deleteContent = !deleteContent);
            y += ROW - 4;
        }
        String sum = days == 0 ? tr("feed.ban.sum.forever") : tr("feed.ban.sum.days", tr("feed.code.sum.days." + days));
        g.drawString(this.font, sum, x, y, RetroUi.FG, false);

        int by = py + ph - PAD - BTN_H;
        button(g, tr("feed.account.cancel"), x, by, 0, BTN_H, RetroUi.FG, false, mouseX, mouseY, this::onClose);
        String ok = tr("feed.ban.submit");
        int ow = buttonWidth(ok);
        button(g, ok, px + pw - PAD - ow, by, ow, BTN_H, RetroUi.ERROR, false, mouseX, mouseY, this::submit);
        if (busy || !status.getString().isEmpty()) {
            drawStatus(g, by - 12);
        }
    }

    private void submit() {
        if (busy) {
            return;
        }
        if (offline) {
            onBanned.accept(days == 0 ? null : System.currentTimeMillis() + days * 86_400_000L);
            onClose();
            return;
        }
        busy = true;
        ModerationApi.ban(userId, days, reason, postId, commentId, postId != null && deleteContent)
                .whenComplete((until, ex) -> this.minecraft.execute(() -> {
                    busy = false;
                    if (ex != null) {
                        setStatus(ModerationApi.describe(ex), RetroUi.ERROR);
                        return;
                    }
                    onBanned.accept(until);
                    onClose();
                }));
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (isEnter(keyCode)) {
            submit();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }
}
