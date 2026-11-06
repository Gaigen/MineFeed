package com.feed.client.screen;

import com.feed.client.CommunityApi;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;

import java.util.Locale;

/** New / edit invite code: own text, note, uses, lifetime, approval; then "code created" with copy. */
public class CodeScreen extends ModalScreen {

    private static final int[] USES = {1, 5, 10, 25, 0};
    private static final int[] DAYS = {1, 7, 30, 0};
    private static final int LABEL_W = 84;
    private static final int ROW = 20;

    private final CommunityScreen page;
    private final String communityId;
    private final CommunityApi.Code editing;
    private EditBox codeBox;
    private EditBox noteBox;
    private String codeText = "";
    private String noteText;
    private int uses;
    /** null = keep the current expiry (editing only); 0 = forever. */
    private Integer days;
    private boolean approval;
    private CommunityApi.Code created;
    private boolean copied;

    public CodeScreen(FeedScreen home, CommunityScreen page, String communityId, CommunityApi.Code editing) {
        super(Component.translatable(editing == null ? "feed.code.new_title" : "feed.code.edit_title"), home);
        this.page = page;
        this.communityId = communityId;
        this.editing = editing;
        if (editing != null) {
            noteText = editing.note() == null ? "" : editing.note();
            uses = editing.maxUses() == null ? 0 : editing.maxUses();
            days = editing.expiresAt() == null ? 0 : null;
            approval = editing.approval();
        } else {
            noteText = "";
            uses = 0;
            days = 0;
            approval = false;
        }
    }

    @Override
    public Component getTitle() {
        if (created != null) {
            return Component.translatable("feed.code.created_title");
        }
        return editing == null ? super.getTitle() : Component.translatable("feed.code.edit_title", editing.code());
    }

    @Override
    protected void init() {
        if (created != null) {
            layout(280, 94);
            return;
        }
        layout(380, 166);
        int fx = innerX() + LABEL_W;
        int fw = innerW() - LABEL_W;
        int y = bodyTop();
        if (editing == null) {
            codeBox = new EditBox(this.font, fx + 4, y + 4, fw - 8, AccountUi.FIELD_H - 6, Component.translatable("feed.code.code")) {
                @Override
                public void insertText(String text) {
                    super.insertText(text.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9-]", ""));
                }
            };
            codeBox.setBordered(false);
            codeBox.setMaxLength(16);
            codeBox.setTextColor(RetroUi.ACCENT);
            codeBox.setValue(codeText);
            codeBox.setResponder(s -> codeText = s);
            addField(codeBox);
            setInitialFocus(codeBox);
        }
        y += ROW + 2;
        noteBox = addField(AccountUi.field(this.font, fx, y, fw, 40, Component.translatable("feed.code.note_hint")));
        noteBox.setValue(noteText);
        noteBox.setResponder(s -> noteText = s);
        if (editing != null) {
            setInitialFocus(noteBox);
        }
    }

    @Override
    protected void renderContent(GuiGraphics g, int mouseX, int mouseY) {
        if (created != null) {
            renderCreated(g, mouseX, mouseY);
            return;
        }
        int x = innerX();
        int fx = x + LABEL_W;
        int y = bodyTop();
        g.drawString(this.font, tr("feed.code.code"), x, y + 4, RetroUi.GRAY, false);
        if (editing == null && codeText.isEmpty()) {
            // the vanilla hint hides while the field is focused
            g.drawString(this.font, tr("feed.code.code_hint"), fx + 4 + (codeBox != null && codeBox.isFocused() ? 6 : 0), y + 4,
                    RetroUi.GRAY, false);
        }
        if (editing != null) {
            g.drawString(this.font, editing.code(), fx, y + 4, RetroUi.ACCENT, false);
        }
        y += ROW + 2;
        g.drawString(this.font, tr("feed.code.note"), x, y + 4, RetroUi.GRAY, false);
        y += ROW + 4;

        g.drawString(this.font, tr("feed.code.uses"), x, y + 2, RetroUi.GRAY, false);
        int cx = fx;
        boolean known = false;
        for (int u : USES) {
            known |= u == uses;
        }
        if (!known) {
            int keep = uses;
            cx += chip(g, String.valueOf(keep), cx, y, RetroUi.ACCENT, true, () -> uses = keep) + 4;
        }
        for (int u : USES) {
            String label = u == 0 ? "\u221e" : String.valueOf(u);
            cx += chip(g, label, cx, y, RetroUi.ACCENT, u == uses, () -> uses = u) + 4;
        }
        y += ROW - 4;

        g.drawString(this.font, tr("feed.code.lifetime"), x, y + 2, RetroUi.GRAY, false);
        cx = fx;
        if (editing != null && editing.expiresAt() != null) {
            String now = page.codeMeta(editing).split(" \u00b7 ")[1];
            cx += chip(g, now, cx, y, RetroUi.ACCENT, days == null, () -> days = null) + 4;
        }
        for (int d : DAYS) {
            String label = d == 0 ? tr("feed.code.forever") : tr("feed.code.days." + d);
            cx += chip(g, label, cx, y, RetroUi.ACCENT, days != null && days == d, () -> days = d) + 4;
        }
        y += ROW - 4;

        g.drawString(this.font, tr("feed.code.mode"), x, y + 2, RetroUi.GRAY, false);
        cx = fx;
        cx += chip(g, tr("feed.manage.codes.instant"), cx, y, RetroUi.ACCENT, !approval, () -> approval = false) + 4;
        chip(g, tr("feed.manage.codes.approval"), cx, y, RetroUi.ACCENT, approval, () -> approval = true);
        y += ROW - 2;

        g.drawString(this.font, tr("feed.code.summary", summary()), x, y, RetroUi.FG, false);

        int by = py + ph - PAD - BTN_H;
        String cancel = tr("feed.account.cancel");
        button(g, cancel, x, by, 0, BTN_H, RetroUi.FG, false, mouseX, mouseY, this::onClose);
        if (editing != null) {
            String revoke = tr("feed.manage.codes.revoke");
            int cw = buttonWidth(cancel);
            button(g, revoke, x + cw + 8, by, 0, BTN_H, RetroUi.ERROR, false, mouseX, mouseY, this::revoke);
        }
        String ok = tr(editing == null ? "feed.code.create" : "feed.manage.settings.save");
        int ow = buttonWidth(ok);
        button(g, ok, px + pw - PAD - ow, by, ow, BTN_H, RetroUi.ACCENT, false, mouseX, mouseY, this::submit);
        if (busy || !status.getString().isEmpty()) {
            drawStatus(g, by - 12);
        }
    }

    /** "до 10 человек, на 7 дней, с одобрением" */
    private String summary() {
        String u = uses == 0 ? tr("feed.code.sum.anyone") : uses == 1 ? tr("feed.code.sum.one") : tr("feed.code.sum.people", uses);
        String d;
        if (days == null) {
            d = tr("feed.code.sum.keep");
        } else {
            d = days == 0 ? tr("feed.code.sum.forever") : tr("feed.code.sum.days." + days);
        }
        String a = tr(approval ? "feed.code.sum.approval" : "feed.code.sum.instant");
        return u + ", " + d + ", " + a;
    }

    private void renderCreated(GuiGraphics g, int mouseX, int mouseY) {
        int x = innerX();
        int w = innerW();
        int y = bodyTop();
        g.drawString(this.font, tr("feed.code.created_hint"), x, y, RetroUi.FG, false);
        y += 13;
        g.fill(x, y, x + w, y + 20, RetroUi.BG);
        g.renderOutline(x, y, w, 20, RetroUi.ACCENT);
        String code = created.code();
        g.drawString(this.font, code, x + (w - this.font.width(code)) / 2, y + 6, RetroUi.ACCENT, false);
        int by = py + ph - PAD - BTN_H;
        String copy = tr(copied ? "feed.manage.codes.copied" : "feed.code.copy");
        button(g, copy, x, by, 0, BTN_H, copied ? AccountUi.GREEN : RetroUi.ACCENT, false, mouseX, mouseY, () -> {
            this.minecraft.keyboardHandler.setClipboard(code);
            copied = true;
        });
        String done = tr("feed.code.done");
        int dw = buttonWidth(done);
        button(g, done, px + pw - PAD - dw, by, dw, BTN_H, RetroUi.FG, false, mouseX, mouseY, this::onClose);
    }

    private void submit() {
        if (busy) {
            return;
        }
        String code = codeText.trim();
        if (editing == null && !code.isEmpty() && (code.length() < 4 || code.startsWith("-") || code.endsWith("-"))) {
            setStatus(Component.translatable("feed.code.bad_code"), RetroUi.ERROR);
            return;
        }
        if (page.offline() && editing == null) {
            created = new CommunityApi.Code(code.isEmpty() ? "MF-PREV-1EW2" : code, noteText, 0, uses == 0 ? null : uses, null, approval, false);
            rebuildWidgets();
            return;
        }
        busy = true;
        setStatus(Component.translatable("feed.screen.loading"), RetroUi.GRAY);
        var f = editing == null
                ? CommunityApi.createCode(communityId, code.isEmpty() ? null : code, noteText, approval, uses, days == null ? 0 : days)
                : CommunityApi.updateCode(communityId, editing.code(), noteText, approval, uses, days);
        f.whenComplete((c, ex) -> this.minecraft.execute(() -> {
            busy = false;
            if (ex != null) {
                setStatus(CommunityApi.describe(ex), RetroUi.ERROR);
                return;
            }
            setStatus(null, RetroUi.ERROR);
            page.codesChanged();
            if (editing == null) {
                created = c;
                rebuildWidgets();
            } else {
                onClose();
            }
        }));
    }

    private void revoke() {
        CommunityApi.Code c = editing;
        this.minecraft.setScreen(new RetroConfirmScreen(this, Component.translatable("feed.manage.codes.revoke"),
                Component.translatable("feed.manage.codes.revoke_confirm", c.code()), Component.translatable("feed.manage.codes.revoke"),
                true, () -> {
            this.minecraft.setScreen(this);
            busy = true;
            CommunityApi.revokeCode(communityId, c.code()).whenComplete((r, ex) -> this.minecraft.execute(() -> {
                busy = false;
                if (ex != null) {
                    setStatus(CommunityApi.describe(ex), RetroUi.ERROR);
                    return;
                }
                page.codesChanged();
                onClose();
            }));
        }));
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (isEnter(keyCode) && created == null) {
            submit();
            return true;
        }
        if (isEnter(keyCode) && created != null) {
            onClose();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }
}
