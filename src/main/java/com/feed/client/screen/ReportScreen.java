package com.feed.client.screen;

import com.feed.client.ModerationApi;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/** Report a post or comment: reason chips, optional details, then a short "thanks". */
@OnlyIn(Dist.CLIENT)
public class ReportScreen extends ModalScreen {

    private static final int LABEL_W = 70;

    private final String postId;
    private final String commentId;
    private EditBox detailsBox;
    private String details = "";
    private String reason;
    /** null = form; otherwise the result line. */
    private Component sent;
    private boolean offline;

    public ReportScreen(FeedScreen home, String postId, String commentId) {
        super(Component.translatable(commentId != null ? "feed.report.title_comment" : "feed.report.title_post"), home);
        this.postId = postId;
        this.commentId = commentId;
    }

    public ReportScreen offline() {
        offline = true;
        return this;
    }

    @Override
    protected void init() {
        if (sent != null) {
            layout(280, 72);
            return;
        }
        layout(360, 124);
        int y = bodyTop() + 32;
        detailsBox = addField(AccountUi.field(this.font, innerX() + LABEL_W, y, innerW() - LABEL_W, 200,
                Component.translatable("feed.report.details_hint")));
        detailsBox.setValue(details);
        detailsBox.setResponder(s -> details = s);
    }

    @Override
    protected void renderContent(GuiGraphics g, int mouseX, int mouseY) {
        int x = innerX();
        int by = py + ph - PAD - BTN_H;
        if (sent != null) {
            g.drawString(this.font, sent, x, bodyTop(), AccountUi.GREEN, false);
            String done = tr("feed.code.done");
            int dw = buttonWidth(done);
            button(g, done, px + pw - PAD - dw, by, dw, BTN_H, RetroUi.FG, false, mouseX, mouseY, this::onClose);
            return;
        }
        int fx = x + LABEL_W;
        int y = bodyTop();
        g.drawString(this.font, tr("feed.report.reason"), x, y + 2, RetroUi.GRAY, false);
        int cx = fx;
        int line = 0;
        for (String r : ModerationApi.REASONS) {
            String label = ModerationScreen.reasonLabel(r);
            int cw = RetroUi.chipWidth(this.font, label);
            if (cx + cw > px + pw - PAD && cx > fx) {
                cx = fx;
                line++;
            }
            cx += chip(g, label, cx, y + line * 15, RetroUi.ERROR, r.equals(reason), () -> {
                reason = r;
                setStatus(null, RetroUi.ERROR);
            }) + 4;
        }
        y += 32;
        g.drawString(this.font, tr("feed.report.details"), x, y + 4, RetroUi.GRAY, false);
        y += 22;
        g.drawString(this.font, tr("feed.report.hint"), x, y, RetroUi.GRAY, false);

        button(g, tr("feed.account.cancel"), x, by, 0, BTN_H, RetroUi.FG, false, mouseX, mouseY, this::onClose);
        String ok = tr("feed.report.send");
        int ow = buttonWidth(ok);
        button(g, ok, px + pw - PAD - ow, by, ow, BTN_H, RetroUi.ERROR, false, mouseX, mouseY, reason == null ? null : this::submit);
        if (busy || !status.getString().isEmpty()) {
            drawStatus(g, by - 12);
        }
    }

    private void submit() {
        if (busy || reason == null) {
            return;
        }
        if (offline) {
            sent = Component.translatable("feed.report.sent");
            rebuildWidgets();
            return;
        }
        busy = true;
        ModerationApi.report(postId, commentId, reason, details).whenComplete((fresh, ex) -> this.minecraft.execute(() -> {
            busy = false;
            if (ex != null) {
                setStatus(ModerationApi.describe(ex), RetroUi.ERROR);
                return;
            }
            sent = Component.translatable(fresh ? "feed.report.sent" : "feed.report.duplicate");
            rebuildWidgets();
        }));
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (isEnter(keyCode)) {
            if (sent != null) {
                onClose();
            } else {
                submit();
            }
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }
}
