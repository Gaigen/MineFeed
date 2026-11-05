package com.feed.client.screen;

import com.feed.client.CommunityApi;
import com.feed.client.FeedApiClient;
import com.feed.client.FeedUtil;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/** Community management: invite codes, members, join requests, settings. Members see members + leave. */
@OnlyIn(Dist.CLIENT)
public class CommunityScreen extends ModalScreen {

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("dd.MM").withZone(ZoneId.systemDefault());
    private static final int ROW_GAP = 4;

    private enum Tab { CODES, MEMBERS, REQUESTS, SETTINGS }

    private CommunityApi.Community community;
    private Tab tab;
    private List<CommunityApi.Code> codes;
    private List<CommunityApi.Member> members;
    private List<CommunityApi.JoinRequest> requests;
    private boolean newApproval;
    private boolean newOnce;
    private String copied;
    private long copiedAt;
    private int scroll;
    private int contentH;
    private EditBox nameBox;
    private EditBox descBox;

    public CommunityScreen(FeedScreen parent, CommunityApi.Community community) {
        super(Component.empty(), parent);
        this.community = community;
        this.tab = community.isMod() ? Tab.CODES : Tab.MEMBERS;
    }

    @Override
    public Component getTitle() {
        return community.isMod() ? Component.translatable("feed.manage.title", community.name()) : Component.literal(community.name());
    }

    @Override
    protected void init() {
        layout(400, 280);
        if (tab == Tab.SETTINGS && community.isOwner()) {
            int y = listTop() + 10;
            nameBox = addField(AccountUi.field(this.font, innerX(), y, innerW(), 32, Component.translatable("feed.manage.settings.name")));
            y += AccountUi.FIELD_H + 4 + 10;
            descBox = addField(AccountUi.field(this.font, innerX(), y, innerW(), 200, Component.translatable("feed.manage.settings.description")));
            nameBox.setValue(community.name());
            descBox.setValue(community.description() == null ? "" : community.description());
        }
        load();
    }

    private List<Tab> tabs() {
        List<Tab> list = new ArrayList<>();
        if (community.isMod()) {
            list.add(Tab.CODES);
        }
        list.add(Tab.MEMBERS);
        if (community.isMod()) {
            list.add(Tab.REQUESTS);
        }
        list.add(Tab.SETTINGS);
        return list;
    }

    private void switchTab(Tab t) {
        if (t == tab) {
            return;
        }
        tab = t;
        scroll = 0;
        setStatus(null, RetroUi.ERROR);
        rebuildWidgets();
    }

    private void load() {
        String id = community.id();
        switch (tab) {
            case CODES -> track(CommunityApi.codes(id).thenAccept(l -> this.minecraft.execute(() -> codes = l)));
            case MEMBERS -> track(CommunityApi.members(id).thenAccept(l -> this.minecraft.execute(() -> members = l)));
            case REQUESTS -> track(CommunityApi.requests(id).thenAccept(l -> this.minecraft.execute(() -> requests = l)));
            default -> { }
        }
    }

    /** Shows errors of background calls in the status line. */
    private void track(CompletableFuture<?> f) {
        f.whenComplete((r, ex) -> {
            if (ex != null) {
                this.minecraft.execute(() -> setStatus(CommunityApi.describe(ex), RetroUi.ERROR));
            }
        });
    }

    /** Runs an action, then reloads the tab and the sidebar. */
    private void act(CompletableFuture<?> f, Component ok) {
        busy = true;
        setStatus(null, RetroUi.ERROR);
        f.whenComplete((r, ex) -> this.minecraft.execute(() -> {
            busy = false;
            if (ex != null) {
                setStatus(CommunityApi.describe(ex), RetroUi.ERROR);
                return;
            }
            if (ok != null) {
                setStatus(ok, AccountUi.GREEN);
            }
            load();
            parent.loadCommunities();
        }));
    }

    private int listTop() {
        return bodyTop() + 4 + RetroUi.CHIP_H + 8;
    }

    private int listBottom() {
        return py + ph - PAD - 12;
    }

    // ---------- render ----------

    @Override
    protected void renderContent(GuiGraphics g, int mouseX, int mouseY) {
        int x = innerX();
        int tx = x;
        int ty = bodyTop() + 4;
        for (Tab t : tabs()) {
            String label = switch (t) {
                case CODES -> tr("feed.manage.tab.codes");
                case MEMBERS -> tr("feed.manage.tab.members") + " " + community.members();
                case REQUESTS -> tr("feed.manage.tab.requests") + (requestsCount() > 0 ? " " + requestsCount() : "");
                case SETTINGS -> tr("feed.manage.tab.settings");
            };
            tx += chip(g, label, tx, ty, t == Tab.REQUESTS && requestsCount() > 0 ? RetroUi.ERROR : RetroUi.FG, t == tab,
                    () -> switchTab(t)) + 6;
        }
        drawStatus(g, listBottom() + 2);

        if (tab == Tab.SETTINGS) {
            renderSettings(g, mouseX, mouseY);
            return;
        }
        int top = listTop();
        int bottom = listBottom() - 2;
        if (tab == Tab.CODES) {
            top = renderCodesHeader(g, top, mouseX, mouseY);
        }
        g.enableScissor(px + 1, top, px + pw - 1, bottom);
        int y = top - scroll;
        int mY = mouseY >= top && mouseY < bottom ? mouseY : -1000;
        switch (tab) {
            case CODES -> y = renderCodes(g, y, mouseX, mY);
            case MEMBERS -> y = renderMembers(g, y, mouseX, mY);
            case REQUESTS -> y = renderRequests(g, y, mouseX, mY);
            default -> { }
        }
        g.disableScissor();
        contentH = y + scroll - top;
        int viewport = bottom - top;
        if (contentH > viewport) {
            int barH = Math.max(10, viewport * viewport / contentH);
            int barY = top + (viewport - barH) * scroll / Math.max(1, contentH - viewport);
            g.fill(px + pw - 4, barY, px + pw - 2, barY + barH, RetroUi.GRAY);
        }
    }

    private int requestsCount() {
        if (requests != null) {
            return requests.size();
        }
        CommunityApi.Community c = CommunityApi.cachedById(community.id());
        return c == null ? 0 : c.requests();
    }

    private int renderCodesHeader(GuiGraphics g, int y, int mouseX, int mouseY) {
        int x = innerX();
        String join = tr("feed.manage.codes.join");
        g.drawString(this.font, join, x, y + 2, RetroUi.GRAY, false);
        int cx = x + this.font.width(join) + 6;
        cx += chip(g, tr("feed.manage.codes.instant"), cx, y, RetroUi.FG, !newApproval, () -> newApproval = false) + 4;
        cx += chip(g, tr("feed.manage.codes.approval"), cx, y, RetroUi.ACCENT, newApproval, () -> newApproval = true) + 10;
        chip(g, tr(newOnce ? "feed.manage.codes.once" : "feed.manage.codes.many"), cx, y, RetroUi.FG, newOnce, () -> newOnce = !newOnce);
        String add = tr("feed.manage.codes.new");
        int bw = buttonWidth(add);
        button(g, add, px + pw - PAD - bw, y - 2, bw, BTN_H, RetroUi.ACCENT, false, mouseX, mouseY,
                () -> act(CommunityApi.createCode(community.id(), newApproval, newOnce ? 1 : null, null), null));
        return y + BTN_H + 6;
    }

    private int renderCodes(GuiGraphics g, int y, int mouseX, int mouseY) {
        int x = innerX();
        int w = innerW();
        if (codes == null) {
            return loadingLine(g, y);
        }
        if (codes.isEmpty()) {
            return emptyLine(g, y, "feed.manage.codes.empty");
        }
        for (CommunityApi.Code c : codes) {
            int h = 26;
            g.fill(x, y, x + w, y + h, RetroUi.HOVER_BG);
            g.drawString(this.font, c.code(), x + 5, y + 4, c.dead() ? RetroUi.GRAY : RetroUi.ACCENT, false);
            String uses = c.uses() + "/" + (c.maxUses() == null ? "\u221e" : c.maxUses());
            String until = c.dead() ? tr("feed.manage.codes.dead")
                    : c.expiresAt() == null ? tr("feed.manage.codes.forever")
                    : tr("feed.manage.codes.until", DAY.format(Instant.ofEpochMilli(c.expiresAt())));
            String meta = tr(c.approval() ? "feed.manage.codes.approval" : "feed.manage.codes.instant") + " \u00b7 " + uses + " \u00b7 " + until;
            String revoke = tr("feed.manage.codes.revoke");
            String copy = copied != null && copied.equals(c.code()) && System.currentTimeMillis() - copiedAt < 2000
                    ? tr("feed.manage.codes.copied") : tr("feed.manage.codes.copy");
            int rw = buttonWidth(revoke);
            int cw = buttonWidth(copy);
            int bx = x + w - 5 - rw;
            g.drawString(this.font, FeedUtil.clip(this.font, meta, bx - cw - 10 - x), x + 5, y + 15, RetroUi.GRAY, false);
            String code = c.code();
            button(g, revoke, bx, y + 5, rw, BTN_H, RetroUi.FG, false, mouseX, mouseY, () -> this.minecraft.setScreen(
                    new RetroConfirmScreen(this, Component.translatable("feed.manage.codes.revoke"),
                            Component.translatable("feed.manage.codes.revoke_confirm", code), Component.translatable("feed.manage.codes.revoke"),
                            false, () -> {
                        this.minecraft.setScreen(this);
                        act(CommunityApi.revokeCode(community.id(), code), null);
                    })));
            if (!c.dead()) {
                button(g, copy, bx - 5 - cw, y + 5, cw, BTN_H, RetroUi.FG, false, mouseX, mouseY, () -> {
                    this.minecraft.keyboardHandler.setClipboard(code);
                    copied = code;
                    copiedAt = System.currentTimeMillis();
                });
            }
            y += h + ROW_GAP;
        }
        AccountUi.drawWrapped(g, this.font, tr("feed.manage.codes.note"), x, y + 2, w, RetroUi.GRAY);
        return y + 14;
    }

    private int renderMembers(GuiGraphics g, int y, int mouseX, int mouseY) {
        int x = innerX();
        int w = innerW();
        if (members == null) {
            return loadingLine(g, y);
        }
        for (CommunityApi.Member m : members) {
            int h = 20;
            g.fill(x, y, x + w, y + h, RetroUi.HOVER_BG);
            boolean self = FeedApiClient.isMine(m.id());
            String role = tr("feed.manage.role." + (m.role() == null ? "member" : m.role()));
            int roleColor = "owner".equals(m.role()) ? RetroUi.ACCENT : "mod".equals(m.role()) ? RetroUi.CYAN : RetroUi.GRAY;
            int bx = x + w - 4;
            String id = m.id();
            String who = m.nick() + " (@" + m.tag() + ")";
            boolean canKick = !self && !"owner".equals(m.role()) && (community.isOwner() || "member".equals(m.role())) && community.isMod();
            if (canKick) {
                String kick = tr("feed.manage.members.kick");
                int kw = buttonWidth(kick);
                bx -= kw;
                button(g, kick, bx, y + 2, kw, BTN_H, RetroUi.ERROR, false, mouseX, mouseY, () -> this.minecraft.setScreen(
                        new RetroConfirmScreen(this, Component.translatable("feed.manage.members.kick"),
                                Component.translatable("feed.manage.members.kick_confirm", who), Component.translatable("feed.manage.members.kick"),
                                true, () -> {
                            this.minecraft.setScreen(this);
                            act(CommunityApi.kick(community.id(), id, false), null);
                        })));
                bx -= 4;
            }
            if (community.isOwner() && !self && !"owner".equals(m.role())) {
                boolean isMod = "mod".equals(m.role());
                String label = tr(isMod ? "feed.manage.members.demote" : "feed.manage.members.promote");
                int lw = buttonWidth(label);
                bx -= lw;
                button(g, label, bx, y + 2, lw, BTN_H, RetroUi.FG, false, mouseX, mouseY,
                        () -> act(CommunityApi.setRole(community.id(), id, isMod ? "member" : "mod"), null));
                bx -= 4;
            }
            int rw = this.font.width(role);
            g.drawString(this.font, role, bx - rw - 4, y + 6, roleColor, false);
            String name = FeedUtil.clip(this.font, m.nick() + " @" + m.tag(), bx - rw - 12 - x - 5);
            g.drawString(this.font, name, x + 5, y + 6, self ? RetroUi.ACCENT : RetroUi.FG, false);
            y += h + ROW_GAP;
        }
        return y;
    }

    private int renderRequests(GuiGraphics g, int y, int mouseX, int mouseY) {
        int x = innerX();
        int w = innerW();
        if (requests == null) {
            return loadingLine(g, y);
        }
        if (requests.isEmpty()) {
            return emptyLine(g, y, "feed.manage.requests.empty");
        }
        for (CommunityApi.JoinRequest r : requests) {
            int h = 26;
            g.fill(x, y, x + w, y + h, RetroUi.HOVER_BG);
            String deny = tr("feed.manage.requests.deny");
            String accept = tr("feed.manage.requests.accept");
            int dw = buttonWidth(deny);
            int aw = buttonWidth(accept);
            int bx = x + w - 5 - dw;
            String id = r.id();
            button(g, deny, bx, y + 5, dw, BTN_H, RetroUi.FG, false, mouseX, mouseY,
                    () -> act(CommunityApi.decide(community.id(), id, false), null));
            button(g, accept, bx - 5 - aw, y + 5, aw, BTN_H, AccountUi.GREEN, false, mouseX, mouseY,
                    () -> act(CommunityApi.decide(community.id(), id, true), null));
            int tw = bx - aw - 15 - x;
            g.drawString(this.font, FeedUtil.clip(this.font, r.nick() + " @" + r.tag(), tw), x + 5, y + 4, RetroUi.FG, false);
            String via = r.via() == null ? "" : tr("feed.manage.requests.via", r.via());
            g.drawString(this.font, FeedUtil.clip(this.font, via, tw), x + 5, y + 15, RetroUi.GRAY, false);
            y += h + ROW_GAP;
        }
        return y;
    }

    private void renderSettings(GuiGraphics g, int mouseX, int mouseY) {
        int x = innerX();
        int y = listTop();
        int by = listBottom() - BTN_H - 6;
        if (community.isOwner()) {
            g.drawString(this.font, tr("feed.manage.settings.name"), x, y, RetroUi.FG, false);
            y += 10 + AccountUi.FIELD_H + 4;
            g.drawString(this.font, tr("feed.manage.settings.description"), x, y, RetroUi.FG, false);
            y += 10 + AccountUi.FIELD_H + 6;
            String save = tr("feed.manage.settings.save");
            button(g, save, x, y, 0, BTN_H, RetroUi.ACCENT, false, mouseX, mouseY, () -> {
                busy = true;
                CommunityApi.update(community.id(), nameBox.getValue().trim(), descBox.getValue().trim())
                        .whenComplete((c, ex) -> this.minecraft.execute(() -> {
                            busy = false;
                            if (ex != null) {
                                setStatus(CommunityApi.describe(ex), RetroUi.ERROR);
                                return;
                            }
                            community = c;
                            setStatus(Component.translatable("feed.manage.settings.saved"), AccountUi.GREEN);
                            parent.loadCommunities();
                        }));
            });
            String del = tr("feed.manage.settings.delete");
            button(g, del, x, by, 0, BTN_H, RetroUi.ERROR, false, mouseX, mouseY, () -> this.minecraft.setScreen(
                    new RetroConfirmScreen(this, Component.translatable("feed.manage.settings.delete"),
                            Component.translatable("feed.manage.settings.delete_confirm", community.name()),
                            Component.translatable("feed.manage.settings.delete"), true,
                            () -> leaveOrDelete(CommunityApi.remove(community.id())))));
        } else {
            AccountUi.drawWrapped(g, this.font, community.description() == null || community.description().isEmpty()
                    ? tr("feed.manage.settings.no_description") : community.description(), x, y, innerW(), RetroUi.FG);
            String leave = tr("feed.manage.settings.leave");
            button(g, leave, x, by, 0, BTN_H, RetroUi.ERROR, false, mouseX, mouseY, () -> this.minecraft.setScreen(
                    new RetroConfirmScreen(this, Component.translatable("feed.manage.settings.leave"),
                            Component.translatable("feed.manage.settings.leave_confirm", community.name()),
                            Component.translatable("feed.manage.settings.leave"), true,
                            () -> leaveOrDelete(CommunityApi.leave(community.id())))));
        }
    }

    private void leaveOrDelete(CompletableFuture<Void> f) {
        this.minecraft.setScreen(this);
        busy = true;
        f.whenComplete((r, ex) -> this.minecraft.execute(() -> {
            busy = false;
            if (ex != null) {
                setStatus(CommunityApi.describe(ex), RetroUi.ERROR);
                return;
            }
            CommunityApi.mine().whenComplete((m, e2) -> this.minecraft.execute(() -> {
                parent.switchSpace("global");
                parent.loadCommunities();
                this.minecraft.setScreen(parent);
            }));
        }));
    }

    private int loadingLine(GuiGraphics g, int y) {
        g.drawString(this.font, tr("feed.screen.loading") + (RetroUi.blinkFast() ? "_" : ""), innerX(), y + 4, RetroUi.GRAY, false);
        return y + 16;
    }

    private int emptyLine(GuiGraphics g, int y, String key) {
        return AccountUi.drawWrapped(g, this.font, tr(key), innerX(), y + 4, innerW(), RetroUi.GRAY);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double dx, double dy) {
        if (super.mouseScrolled(mouseX, mouseY, dx, dy)) {
            return true;
        }
        int viewport = listBottom() - 2 - listTop();
        scroll = Math.max(0, Math.min(Math.max(0, contentH - viewport), scroll - (int) (dy * 16)));
        return true;
    }
}
