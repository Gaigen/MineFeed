package com.feed.client.screen;

import com.feed.client.CommunityApi;
import com.feed.client.FeedApiClient;
import com.feed.client.FeedTime;
import com.feed.client.FeedUtil;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.MultiLineEditBox;
import net.minecraft.client.gui.screens.ConfirmLinkScreen;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Community page: invite codes, members, join requests and settings for mods;
 * members see the member list and "about" with a leave button.
 */
@OnlyIn(Dist.CLIENT)
public class CommunityScreen extends ShellScreen {

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("dd.MM").withZone(ZoneId.systemDefault());
    private static final int TABS_Y = 24;
    private static final int LIST_Y = 42;
    private static final int GAP = 5;
    private static final int BTN_H = 13;
    private static final int LABEL_W = 78;
    private static final int ROW = 22;
    private static final int ABOUT_H = 40;

    public enum Tab { CODES, MEMBERS, REQUESTS, SETTINGS }

    /** Clickable area of this frame; top/bottom = visible band (for rows inside the scrolled list). */
    private record Btn(int x, int y, int w, int h, int top, int bottom, Runnable action) {}

    private CommunityApi.Community community;
    private Tab tab;
    private List<CommunityApi.Code> codes;
    private List<CommunityApi.Member> members;
    private List<CommunityApi.JoinRequest> requests;
    private String copied;
    private long copiedAt;
    private int scroll;
    private int contentHeight;
    private boolean busy;
    private Component status;
    private int statusColor = RetroUi.ERROR;
    private final List<Btn> btns = new ArrayList<>();
    private int clipTop = 0;
    private int clipBottom = 100_000;
    private EditBox nameBox;
    private EditBox descBox;
    private MultiLineEditBox aboutBox;
    private EditBox linksBox;
    /** Unsaved settings survive a resize: name, description, about, links. */
    private String[] draft;
    /** Preview builds only: data is given, nothing is loaded. */
    private boolean offline;

    public CommunityScreen(FeedScreen home, CommunityApi.Community community) {
        super(Component.literal(community.name()), home);
        this.community = community;
        this.tab = community.isMod() ? Tab.CODES : Tab.MEMBERS;
    }

    /** Opens on the given tab (e.g. requests from a notification). */
    public CommunityScreen tab(Tab t) {
        if (tabs().contains(t)) {
            this.tab = t;
        }
        return this;
    }

    /** Dev preview: show fixed data instead of loading it. */
    public CommunityScreen preview(List<CommunityApi.Code> codes, List<CommunityApi.Member> members,
                                   List<CommunityApi.JoinRequest> requests) {
        this.codes = codes;
        this.members = members;
        this.requests = requests;
        this.offline = true;
        return this;
    }

    boolean offline() {
        return offline;
    }

    public CommunityApi.Community community() {
        return community;
    }

    @Override
    protected String navId() {
        return "c:" + community.id();
    }

    @Override
    protected void init() {
        Component back = Component.translatable("feed.profile.back");
        addRenderableWidget(new RetroButton(contentLeft(), 5, this.font.width(back) + 12, BTN_H, back, b -> openFeed(community.id())));
        if (tab == Tab.CODES && community.isMod()) {
            Component add = Component.translatable("feed.manage.codes.new");
            int w = this.font.width(add) + 12;
            addRenderableWidget(new RetroButton(contentLeft() + contentWidth() - w, TABS_Y - 1, w, BTN_H, add,
                    b -> editCode(null)).color(RetroUi.ACCENT));
        }
        nameBox = null;
        descBox = null;
        aboutBox = null;
        linksBox = null;
        if (tab == Tab.SETTINGS && community.isOwner()) {
            if (draft == null) {
                draft = new String[] {community.name(), nz(community.description()), nz(community.about()), nz(community.links())};
            }
            int fx = contentLeft() + LABEL_W;
            int fw = contentWidth() - LABEL_W;
            int y = LIST_Y;
            nameBox = addRenderableWidget(AccountUi.field(this.font, fx, y, fw, 32, Component.translatable("feed.manage.settings.name")));
            y += ROW;
            descBox = addRenderableWidget(AccountUi.field(this.font, fx, y, fw, 200, Component.translatable("feed.manage.settings.description")));
            y += ROW;
            aboutBox = new RetroTextArea(this.font, fx, y, fw, ABOUT_H, Component.translatable("feed.apply.about_hint"), Component.empty());
            aboutBox.setCharacterLimit(1000);
            addRenderableWidget(aboutBox);
            y += ABOUT_H + 12;
            linksBox = addRenderableWidget(AccountUi.field(this.font, fx, y, fw, 300, Component.translatable("feed.apply.links_hint")));
            nameBox.setValue(draft[0]);
            descBox.setValue(draft[1]);
            aboutBox.setValue(draft[2]);
            linksBox.setValue(draft[3]);
            nameBox.setResponder(v -> draft[0] = v);
            descBox.setResponder(v -> draft[1] = v);
            aboutBox.setValueListener(v -> draft[2] = v);
            linksBox.setResponder(v -> draft[3] = v);
        }
        load();
    }

    private static String nz(String s) {
        return s == null ? "" : s;
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
        status = null;
        rebuildWidgets();
    }

    // ---------- data ----------

    private void load() {
        if (offline) {
            return;
        }
        String id = community.id();
        switch (tab) {
            case CODES -> track(CommunityApi.codes(id).thenAccept(l -> this.minecraft.execute(() -> codes = l)));
            case MEMBERS -> track(CommunityApi.members(id).thenAccept(l -> this.minecraft.execute(() -> members = l)));
            case REQUESTS -> track(CommunityApi.requests(id).thenAccept(l -> this.minecraft.execute(() -> requests = l)));
            default -> { }
        }
        if (community.isMod() && tab != Tab.REQUESTS) {
            CommunityApi.requests(id).thenAccept(l -> this.minecraft.execute(() -> requests = l));
        }
    }

    private void track(CompletableFuture<?> f) {
        f.whenComplete((r, ex) -> {
            if (ex != null) {
                this.minecraft.execute(() -> setStatus(CommunityApi.describe(ex), RetroUi.ERROR));
            }
        });
    }

    private void setStatus(Component text, int color) {
        status = text;
        statusColor = color;
    }

    /** Runs an action, then reloads the tab and the sidebar. */
    private void act(CompletableFuture<?> f, Component ok) {
        busy = true;
        status = null;
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
            home().loadCommunities();
        }));
    }

    /** Called by the code dialog after a change. */
    void codesChanged() {
        load();
    }

    private void editCode(CommunityApi.Code code) {
        CodeScreen s = new CodeScreen(home(), this, community.id(), code);
        s.from(this);
        this.minecraft.setScreen(s);
    }

    private void confirm(String titleKey, Component message, boolean danger, Runnable yes) {
        this.minecraft.setScreen(new RetroConfirmScreen(this, Component.translatable(titleKey), message,
                Component.translatable(titleKey), danger, () -> {
            this.minecraft.setScreen(this);
            yes.run();
        }));
    }

    // ---------- rendering ----------

    @Override
    public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.renderBackground(g, mouseX, mouseY, partialTick);
        for (EditBox f : new EditBox[] {nameBox, descBox, linksBox}) {
            if (f != null) {
                AccountUi.drawField(g, f);
            }
        }
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        btns.clear();
        super.render(g, mouseX, mouseY, partialTick);
        int x = contentLeft();
        int w = contentWidth();
        int backW = this.font.width(Component.translatable("feed.profile.back")) + 12;
        int tx = x + backW + 8;
        PixelIcon.draw(g, community.isOwner() ? "crown" : "lock", tx, TITLE_Y, RetroUi.ACCENT);
        drawTitle(g, community.name(), tr(community.isMod() ? "feed.manage.sub" : "feed.manage.sub_member"),
                tx + 10, TITLE_Y, x + w);

        int cx = x;
        for (Tab t : tabs()) {
            String label = switch (t) {
                case CODES -> tr("feed.manage.tab.codes");
                case MEMBERS -> tr("feed.manage.tab.members") + " " + community.members();
                case REQUESTS -> tr("feed.manage.tab.requests") + (requestsCount() > 0 ? " " + requestsCount() : "");
                case SETTINGS -> tr(community.isOwner() ? "feed.manage.tab.settings" : "feed.manage.tab.about");
            };
            int color = t == Tab.REQUESTS && requestsCount() > 0 && t != tab ? RetroUi.ERROR : RetroUi.ACCENT;
            cx += chip(g, label, cx, TABS_Y, color, t == tab, mouseX, mouseY, () -> switchTab(t)) + 3;
        }

        if (tab == Tab.SETTINGS) {
            renderSettings(g, x, w, mouseX, mouseY);
        } else {
            int top = LIST_Y;
            int bottom = this.height - (status != null ? 16 : 4);
            boolean in = mouseY >= top && mouseY < bottom;
            int mx = in ? mouseX : -1;
            int my = in ? mouseY : -1;
            g.enableScissor(sidebarW(), top, this.width, bottom);
            clipTop = top;
            clipBottom = bottom;
            int y = top - scroll;
            y = switch (tab) {
                case CODES -> renderCodes(g, x, y, w, mx, my);
                case MEMBERS -> renderMembers(g, x, y, w, mx, my);
                case REQUESTS -> renderRequests(g, x, y, w, mx, my);
                default -> y;
            };
            clipTop = 0;
            clipBottom = 100_000;
            g.disableScissor();
            contentHeight = y + scroll - top;
            drawScrollbar(g, top, bottom, scroll, contentHeight);
        }
        if (status != null) {
            String s = FeedUtil.clip(this.font, status.getString(), w);
            g.drawString(this.font, s, x, this.height - 12, statusColor, false);
        }
    }

    private int requestsCount() {
        if (requests != null) {
            return requests.size();
        }
        CommunityApi.Community c = CommunityApi.cachedById(community.id());
        return c == null ? 0 : c.requests();
    }

    private int renderCodes(GuiGraphics g, int x, int y, int w, int mouseX, int mouseY) {
        if (codes == null) {
            return loadingLine(g, x, y);
        }
        if (codes.isEmpty()) {
            return emptyLine(g, x, y, w, "feed.manage.codes.empty");
        }
        for (CommunityApi.Code c : codes) {
            int h = 28;
            boolean dead = c.dead();
            RetroUi.drawBox(g, x, y, w, h, false);
            if (dead) {
                g.renderOutline(x, y, w, h, RetroUi.SHADOW);
            }
            int bx = x + w - 6;
            int by = y + 8;
            String edit = tr("feed.manage.codes.edit");
            bx -= RetroUi.buttonWidth(this.font, edit);
            button(g, edit, bx, by, RetroUi.FG, mouseX, mouseY, () -> editCode(c));
            if (dead) {
                String d = tr("feed.manage.codes.dead");
                bx -= this.font.width(d) + 8;
                g.drawString(this.font, d, bx, y + 10, RetroUi.GRAY, false);
            } else {
                boolean justCopied = c.code().equals(copied) && System.currentTimeMillis() - copiedAt < 2000;
                String copy = tr(justCopied ? "feed.manage.codes.copied" : "feed.manage.codes.copy");
                bx -= RetroUi.buttonWidth(this.font, copy) + 4;
                button(g, copy, bx, by, justCopied ? AccountUi.GREEN : RetroUi.FG, mouseX, mouseY, () -> {
                    this.minecraft.keyboardHandler.setClipboard(c.code());
                    copied = c.code();
                    copiedAt = System.currentTimeMillis();
                });
            }
            int textW = bx - 8 - (x + 6);
            String code = FeedUtil.clip(this.font, c.code(), textW);
            g.drawString(this.font, code, x + 6, y + 5, dead ? RetroUi.GRAY : RetroUi.ACCENT, false);
            if (!nz(c.note()).isEmpty()) {
                int nx = x + 6 + this.font.width(code) + 6;
                if (textW - (nx - x - 6) > 20) {
                    g.drawString(this.font, FeedUtil.clip(this.font, c.note(), textW - (nx - x - 6)), nx, y + 5,
                            dead ? RetroUi.GRAY : RetroUi.FG, false);
                }
            }
            g.drawString(this.font, FeedUtil.clip(this.font, codeMeta(c), textW), x + 6, y + 16, RetroUi.GRAY, false);
            y += h + GAP;
        }
        return AccountUi.drawWrapped(g, this.font, tr("feed.manage.codes.note"), x, y + 2, w, RetroUi.GRAY) + 4;
    }

    /** "3/10 исп. · до 12.06 · по заявке" */
    String codeMeta(CommunityApi.Code c) {
        String uses = tr("feed.manage.codes.uses", c.uses(), c.maxUses() == null ? "\u221e" : String.valueOf(c.maxUses()));
        long now = System.currentTimeMillis();
        String until = c.expiresAt() == null ? tr("feed.manage.codes.forever")
                : c.expiresAt() <= now ? tr("feed.manage.codes.expired", DAY.format(Instant.ofEpochMilli(c.expiresAt())))
                : tr("feed.manage.codes.until", DAY.format(Instant.ofEpochMilli(c.expiresAt())));
        String mode = tr(c.approval() ? "feed.manage.codes.approval" : "feed.manage.codes.instant");
        return uses + " \u00b7 " + until + " \u00b7 " + mode;
    }

    private int renderMembers(GuiGraphics g, int x, int y, int w, int mouseX, int mouseY) {
        if (members == null) {
            return loadingLine(g, x, y);
        }
        for (CommunityApi.Member m : members) {
            int h = 26;
            boolean self = FeedApiClient.isMine(m.id());
            boolean owner = "owner".equals(m.role());
            boolean mod = "mod".equals(m.role());
            RetroUi.drawBox(g, x, y, w, h, false);
            if (owner) {
                g.renderOutline(x, y, w, h, RetroUi.ACCENT);
            }
            int bx = x + w - 6;
            int by = y + 7;
            String id = m.id();
            String who = m.nick() + " (@" + m.tag() + ")";
            boolean canKick = community.isMod() && !self && !owner && (community.isOwner() || !mod);
            if (canKick) {
                String kick = tr("feed.manage.members.kick");
                bx -= RetroUi.buttonWidth(this.font, kick);
                button(g, kick, bx, by, RetroUi.FG, mouseX, mouseY, () -> confirm("feed.manage.members.kick",
                        Component.translatable("feed.manage.members.kick_confirm", who), true,
                        () -> act(CommunityApi.kick(community.id(), id, false), null)));
                bx -= 4;
            }
            if (community.isOwner() && !self && !owner) {
                String label = tr(mod ? "feed.manage.members.demote" : "feed.manage.members.promote");
                bx -= RetroUi.buttonWidth(this.font, label);
                button(g, label, bx, by, RetroUi.FG, mouseX, mouseY,
                        () -> act(CommunityApi.setRole(community.id(), id, mod ? "member" : "mod"), null));
                bx -= 4;
            }
            String key = FeedApiClient.avatarKey(m.id(), m.avatar());
            RetroUi.drawAvatar(g, this.font, key, m.nick(), x + 5, y + 5, 16);
            int nx = x + 26;
            int limit = bx - 6;
            boolean hoverName = mouseX >= x && mouseX < nx + this.font.width(m.nick()) && mouseY >= y && mouseY < y + h && mouseX < limit;
            String nick = FeedUtil.clip(this.font, m.nick(), Math.max(10, limit - nx));
            g.drawString(this.font, nick, nx, y + 4, self ? RetroUi.ACCENT : hoverName ? RetroUi.STRONG : RetroUi.FG, false);
            int tagX = nx + this.font.width(nick) + 4;
            if (tagX < limit - 20) {
                g.drawString(this.font, FeedUtil.clip(this.font, "@" + m.tag(), limit - tagX), tagX, y + 4, RetroUi.GRAY, false);
            }
            hit(x, y, Math.max(26, nx + this.font.width(nick) - x), h, () -> openPlayer(id));
            String role = tr("feed.manage.role_line." + (m.role() == null ? "member" : m.role()));
            int roleColor = owner ? RetroUi.ACCENT : mod ? RetroUi.CYAN : RetroUi.GRAY;
            g.drawString(this.font, role, nx, y + 15, roleColor, false);
            String via = m.via() != null && !m.via().isEmpty() ? tr("feed.manage.requests.via", via(m.via())) : "";
            String since = m.joinedAt() > 0 ? tr("feed.manage.members.since", DAY.format(Instant.ofEpochMilli(m.joinedAt()))) : "";
            String tail = owner ? tr("feed.manage.members.created")
                    : via.isEmpty() ? since : since.isEmpty() ? via : via + " \u00b7 " + since;
            if (!tail.isEmpty()) {
                int rx = nx + this.font.width(role);
                g.drawString(this.font, FeedUtil.clip(this.font, " \u00b7 " + tail, Math.max(0, limit - rx)), rx, y + 15,
                        owner ? RetroUi.ACCENT : RetroUi.GRAY, false);
            }
            y += h + GAP;
        }
        return y;
    }

    private int renderRequests(GuiGraphics g, int x, int y, int w, int mouseX, int mouseY) {
        if (requests == null) {
            return loadingLine(g, x, y);
        }
        if (requests.isEmpty()) {
            return emptyLine(g, x, y, w, "feed.manage.requests.empty");
        }
        for (CommunityApi.JoinRequest r : requests) {
            int h = 40;
            RetroUi.drawBox(g, x, y, w, h, false);
            g.renderOutline(x, y, w, h, RetroUi.ACCENT);
            RetroUi.drawAvatar(g, this.font, FeedApiClient.avatarKey(r.id(), r.avatar()), r.nick(), x + 5, y + 5, 16);
            int nx = x + 26;
            String nick = FeedUtil.clip(this.font, r.nick(), w - 40);
            g.drawString(this.font, nick, nx, y + 4, RetroUi.FG, false);
            String tail = "@" + r.tag() + (r.createdAt() > 0 ? " \u00b7 " + FeedTime.ago(Instant.ofEpochMilli(r.createdAt()).toString()) : "");
            int tagX = nx + this.font.width(nick) + 4;
            g.drawString(this.font, FeedUtil.clip(this.font, tail, x + w - 6 - tagX), tagX, y + 4, RetroUi.GRAY, false);
            String id = r.id();
            hit(x, y, Math.max(26, nx + this.font.width(nick) - x), 14, () -> openPlayer(id));
            String via = r.via() == null || r.via().isEmpty() ? "" : tr("feed.manage.requests.via", via(r.via()));
            g.drawString(this.font, FeedUtil.clip(this.font, via, w - 32), nx, y + 14, RetroUi.GRAY, false);
            String accept = tr("feed.manage.requests.accept");
            int aw = button(g, accept, nx, y + 24, AccountUi.GREEN, mouseX, mouseY,
                    () -> act(CommunityApi.decide(community.id(), id, true), Component.translatable("feed.notifs.accepted_done")));
            button(g, tr("feed.manage.requests.deny"), nx + aw + 4, y + 24, RetroUi.FG, mouseX, mouseY,
                    () -> act(CommunityApi.decide(community.id(), id, false), Component.translatable("feed.notifs.declined_done")));
            y += h + GAP;
        }
        return y;
    }

    private void renderSettings(GuiGraphics g, int x, int w, int mouseX, int mouseY) {
        int y = LIST_Y;
        if (community.isOwner()) {
            label(g, "feed.manage.settings.name", x, y);
            y += ROW;
            label(g, "feed.manage.settings.description", x, y);
            y += ROW;
            label(g, "feed.manage.settings.about", x, y);
            y += ABOUT_H + 12;
            label(g, "feed.manage.settings.links", x, y);
            y += ROW;
            y = discordRow(g, x, y, w, mouseX, mouseY);
            y = nsfwRow(g, x, y, w, mouseX, mouseY) + 4;
            boolean changed = draft != null && (!draft[0].trim().equals(community.name()) || !draft[1].trim().equals(nz(community.description()))
                    || !draft[2].trim().equals(nz(community.about())) || !draft[3].trim().equals(nz(community.links())));
            String save = tr("feed.manage.settings.save");
            button(g, save, x + LABEL_W, y, changed ? RetroUi.ACCENT : RetroUi.GRAY, mouseX, mouseY, changed ? this::save : null);
            String del = tr("feed.manage.settings.delete");
            button(g, del, x + w - RetroUi.buttonWidth(this.font, del), y, RetroUi.ERROR, mouseX, mouseY,
                    () -> confirm("feed.manage.settings.delete",
                            Component.translatable("feed.manage.settings.delete_confirm", community.name()), true,
                            () -> leaveOrDelete(CommunityApi.remove(community.id()))));
            return;
        }
        int vx = x + LABEL_W;
        int vw = w - LABEL_W;
        label(g, "feed.manage.settings.description", x, y);
        String desc = nz(community.description()).isEmpty() ? tr("feed.manage.settings.no_description") : community.description();
        y = Math.max(y + ROW - 4, AccountUi.drawWrapped(g, this.font, desc, vx, y + 4, vw, RetroUi.FG) + 6);
        if (!nz(community.about()).isEmpty()) {
            label(g, "feed.manage.settings.about", x, y);
            y = Math.max(y + ROW - 4, AccountUi.drawWrapped(g, this.font, community.about(), vx, y + 4, vw, RetroUi.FG) + 6);
        }
        if (!nz(community.links()).isEmpty()) {
            label(g, "feed.manage.settings.links", x, y);
            y = Math.max(y + ROW - 4, AccountUi.drawWrapped(g, this.font, community.links(), vx, y + 4, vw, RetroUi.CYAN) + 6);
        }
        label(g, "feed.manage.settings.role", x, y);
        String role = tr("feed.manage.role_line." + (community.role() == null ? "member" : community.role()));
        g.drawString(this.font, role, vx, y + 4, community.isMod() ? RetroUi.CYAN : RetroUi.FG, false);
        y += ROW;
        y = discordRow(g, x, y, w, mouseX, mouseY);
        if (community.nsfw()) {
            y = nsfwRow(g, x, y, w, mouseX, mouseY);
        }
        y += 4;
        String leave = tr("feed.manage.settings.leave");
        button(g, leave, x, y, RetroUi.ERROR, mouseX, mouseY, () -> confirm("feed.manage.settings.leave",
                Component.translatable("feed.manage.settings.leave_confirm", community.name()), true,
                () -> leaveOrDelete(CommunityApi.leave(community.id()))));
    }

    private void label(GuiGraphics g, String key, int x, int y) {
        g.drawString(this.font, tr(key), x, y + 4, RetroUi.GRAY, false);
    }

    private int discordRow(GuiGraphics g, int x, int y, int w, int mouseX, int mouseY) {
        String url = community.discordUrl();
        if (url == null) {
            return y;
        }
        label(g, "feed.manage.settings.discord", x, y);
        g.drawString(this.font, tr("feed.manage.settings.forum"), x + LABEL_W, y + 4, RetroUi.FG, false);
        String open = tr("feed.manage.settings.open");
        button(g, open, x + w - RetroUi.buttonWidth(this.font, open), y + 1, RetroUi.FG, mouseX, mouseY,
                () -> ConfirmLinkScreen.confirmLinkNow(this, url));
        return y + ROW;
    }

    /** 18+: the owner can turn it on (with a warning); only a server moderator can turn it off. */
    private int nsfwRow(GuiGraphics g, int x, int y, int w, int mouseX, int mouseY) {
        label(g, "feed.nsfw.label", x, y);
        String value = tr(community.nsfw() ? "feed.nsfw.community_on" : "feed.nsfw.community_off");
        int bw = 0;
        if (community.isOwner() && !community.nsfw()) {
            String on = tr("feed.nsfw.turn_on");
            bw = RetroUi.buttonWidth(this.font, on) + 6;
            button(g, on, x + w - RetroUi.buttonWidth(this.font, on), y + 1, RetroUi.ERROR, mouseX, mouseY, this::confirmNsfw);
        }
        g.drawString(this.font, FeedUtil.clip(this.font, value, w - LABEL_W - bw), x + LABEL_W, y + 4,
                community.nsfw() ? RetroUi.ERROR : RetroUi.FG, false);
        return y + ROW;
    }

    private void confirmNsfw() {
        var acc = com.feed.client.account.AccountManager.INSTANCE.account();
        boolean needAge = acc == null || !acc.showNsfw();
        confirm("feed.nsfw.community_title", Component.translatable(needAge ? "feed.nsfw.community_confirm_age" : "feed.nsfw.community_confirm"),
                true, () -> {
                    busy = true;
                    status = null;
                    CompletableFuture<?> age = needAge
                            ? com.feed.client.account.AccountManager.INSTANCE.api().setShowNsfw(true).thenAccept(a ->
                            this.minecraft.execute(() -> com.feed.client.account.AccountManager.INSTANCE.updateAccount(a)))
                            : CompletableFuture.completedFuture(null);
                    age.thenCompose(v -> CommunityApi.setNsfw(community.id(), true)).whenComplete((c, ex) -> this.minecraft.execute(() -> {
                        busy = false;
                        if (ex != null) {
                            setStatus(CommunityApi.describe(ex), RetroUi.ERROR);
                            return;
                        }
                        community = c;
                        setStatus(Component.translatable("feed.nsfw.community_saved"), AccountUi.GREEN);
                        home().loadCommunities();
                    }));
                });
    }

    private void save() {
        busy = true;
        status = null;
        CommunityApi.update(community.id(), draft[0].trim(), draft[1].trim(), draft[2].trim(), draft[3].trim())
                .whenComplete((c, ex) -> this.minecraft.execute(() -> {
                    busy = false;
                    if (ex != null) {
                        setStatus(CommunityApi.describe(ex), RetroUi.ERROR);
                        return;
                    }
                    community = c;
                    setStatus(Component.translatable("feed.manage.settings.saved"), AccountUi.GREEN);
                    home().loadCommunities();
                }));
    }

    private void leaveOrDelete(CompletableFuture<Void> f) {
        busy = true;
        f.whenComplete((r, ex) -> this.minecraft.execute(() -> {
            busy = false;
            if (ex != null) {
                setStatus(CommunityApi.describe(ex), RetroUi.ERROR);
                return;
            }
            CommunityApi.mine().whenComplete((m, e2) -> this.minecraft.execute(() -> {
                home().loadCommunities();
                openFeed("global");
            }));
        }));
    }

    /** "MF-8Q2K-7XPL" as is, a note in quotes: «для Discord-канала». */
    private static String via(String v) {
        return v.matches("[A-Z0-9-]+") ? v : "\u00ab" + v + "\u00bb";
    }

    private int loadingLine(GuiGraphics g, int x, int y) {
        g.drawString(this.font, tr("feed.screen.loading") + (RetroUi.blinkFast() ? "_" : ""), x + 6, y + 6, RetroUi.GRAY, false);
        return y + 18;
    }

    private int emptyLine(GuiGraphics g, int x, int y, int w, String key) {
        return AccountUi.drawWrapped(g, this.font, tr(key), x + 6, y + 6, w - 12, RetroUi.GRAY);
    }

    // ---------- immediate-mode controls ----------

    private int button(GuiGraphics g, String label, int x, int y, int color, int mouseX, int mouseY, Runnable action) {
        int w = RetroUi.buttonWidth(this.font, label);
        boolean enabled = action != null && !busy;
        boolean hover = enabled && mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + BTN_H;
        RetroUi.drawButton(g, this.font, label, x, y, w, BTN_H, hover, enabled, color);
        if (enabled) {
            hit(x, y, w, BTN_H, action);
        }
        return w;
    }

    private int chip(GuiGraphics g, String label, int x, int y, int color, boolean on, int mouseX, int mouseY, Runnable action) {
        int w = RetroUi.chipWidth(this.font, label);
        boolean hover = !on && mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + RetroUi.CHIP_H;
        RetroUi.drawChip(g, this.font, label, x, y, hover ? RetroUi.FG : color, on);
        hit(x, y, w, RetroUi.CHIP_H, action);
        return w;
    }

    private void hit(int x, int y, int w, int h, Runnable action) {
        btns.add(new Btn(x, y, w, h, clipTop, clipBottom, action));
    }

    // ---------- input ----------

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (super.mouseClicked(mouseX, mouseY, button)) {
            return true;
        }
        if (button != 0 || busy) {
            return false;
        }
        for (Btn b : new ArrayList<>(btns)) {
            if (mouseX >= b.x() && mouseX < b.x() + b.w() && mouseY >= b.y() && mouseY < b.y() + b.h()
                    && mouseY >= b.top() && mouseY < b.bottom()) {
                b.action().run();
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double dx, double dy) {
        if (super.mouseScrolled(mouseX, mouseY, dx, dy)) {
            return true;
        }
        int viewport = this.height - 4 - LIST_Y;
        scroll = Math.max(0, Math.min(Math.max(0, contentHeight - viewport), scroll - (int) (dy * SCROLL_STEP)));
        return true;
    }
}
