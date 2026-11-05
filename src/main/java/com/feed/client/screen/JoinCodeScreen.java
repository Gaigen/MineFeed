package com.feed.client.screen;

import com.feed.client.CommunityApi;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

import java.util.Locale;

/** "Join a community": one field for the invite code. */
@OnlyIn(Dist.CLIENT)
public class JoinCodeScreen extends ModalScreen {

    private EditBox code;
    private int fieldY;
    private String keep = "";

    public JoinCodeScreen(FeedScreen parent) {
        super(Component.translatable("feed.join.title"), parent);
    }

    @Override
    protected void init() {
        layout(270, 100);
        fieldY = bodyTop() + 14;
        code = new EditBox(this.font, innerX() + 4, fieldY + 4, innerW() - 8, AccountUi.FIELD_H - 6, Component.translatable("feed.join.title")) {
            @Override
            public void insertText(String text) {
                super.insertText(text.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9-]", ""));
            }
        };
        code.setBordered(false);
        code.setMaxLength(16);
        code.setTextColor(RetroUi.ACCENT);
        code.setHint(Component.literal("MF-XXXX-XXXX").withColor(RetroUi.GRAY & 0xFFFFFF));
        code.setValue(keep);
        code.setResponder(s -> keep = s);
        addField(code);
        setInitialFocus(code);
    }

    @Override
    protected void renderContent(GuiGraphics g, int mouseX, int mouseY) {
        g.drawString(this.font, tr("feed.join.label"), innerX(), bodyTop(), RetroUi.FG, false);
        int by = py + ph - PAD - BTN_H;
        String label = tr("feed.join.submit");
        int bw = buttonWidth(label);
        button(g, label, px + pw - PAD - bw, by, bw, BTN_H, RetroUi.ACCENT, false, mouseX, mouseY, this::submit);
        if (busy || !status.getString().isEmpty()) {
            drawStatus(g, fieldY + AccountUi.FIELD_H + 6);
        } else {
            g.drawString(this.font, tr("feed.join.esc"), innerX(), by + 4, RetroUi.GRAY, false);
        }
    }

    private void submit() {
        String value = code.getValue().trim();
        if (value.length() < 4 || busy) {
            return;
        }
        busy = true;
        setStatus(null, RetroUi.ERROR);
        CommunityApi.join(value).whenComplete((r, ex) -> this.minecraft.execute(() -> {
            busy = false;
            if (ex != null) {
                if ("need_18".equals(CommunityApi.error(ex).code)) {
                    confirm18();
                    return;
                }
                setStatus(CommunityApi.describe(ex), RetroUi.ERROR);
                return;
            }
            if ("pending".equals(r.status())) {
                parent.loadCommunities();
                setStatus(Component.translatable("feed.join.pending", r.community().name()), AccountUi.GREEN);
                return;
            }
            CommunityApi.mine().whenComplete((m, e2) -> this.minecraft.execute(() -> {
                parent.switchSpace(r.community().id());
                this.minecraft.setScreen(parent);
            }));
        }));
    }

    /** An 18+ community: joining turns on "Show 18+" after the age confirmation, then tries again. */
    private void confirm18() {
        this.minecraft.setScreen(new RetroConfirmScreen(this,
                Component.translatable("feed.nsfw.join_title"),
                Component.translatable("feed.nsfw.join_confirm"),
                Component.translatable("feed.nsfw.confirm_yes"), true, () -> {
            this.minecraft.setScreen(this);
            busy = true;
            com.feed.client.account.AccountManager.thenOnMain(
                    com.feed.client.account.AccountManager.INSTANCE.api().setShowNsfw(true), updated -> {
                        busy = false;
                        com.feed.client.account.AccountManager.INSTANCE.updateAccount(updated);
                        submit();
                    }, err -> {
                        busy = false;
                        setStatus(AccountUi.describe(err), RetroUi.ERROR);
                    });
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
