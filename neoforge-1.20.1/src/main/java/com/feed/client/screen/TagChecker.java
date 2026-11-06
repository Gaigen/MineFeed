package com.feed.client.screen;

import com.feed.client.account.AccountApi;
import com.feed.client.account.AccountManager;
import com.feed.client.account.TagRules;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

/** Live "@tag is free?" check: local rules instantly, server check after a short pause in typing. */
public final class TagChecker {

    public enum State { EMPTY, INVALID, WAITING, CHECKING, FREE, TAKEN, OWN, ERROR }

    private static final long DEBOUNCE_MS = 450;

    private final String ownTag;
    private String value = "";
    private State state = State.EMPTY;
    private Component message = Component.empty();
    private long typedAt;
    private int generation;
    private boolean claim;

    public TagChecker(String ownTag) {
        this.ownTag = ownTag;
    }

    public void onChanged(String raw) {
        value = TagRules.normalize(raw);
        generation++;
        claim = false;
        if (value.isEmpty()) {
            set(State.EMPTY, Component.empty());
            return;
        }
        if (value.equals(ownTag)) {
            set(State.OWN, Component.translatable("feed.account.tag.own"));
            return;
        }
        TagRules.Problem p = TagRules.check(value);
        if (p != TagRules.Problem.NONE) {
            set(State.INVALID, AccountUi.problem(p));
            return;
        }
        typedAt = System.currentTimeMillis();
        set(State.WAITING, Component.translatable("feed.account.tag.checking"));
    }

    public void tick() {
        if (state != State.WAITING || System.currentTimeMillis() - typedAt < DEBOUNCE_MS) {
            return;
        }
        final int gen = generation;
        final String tag = value;
        set(State.CHECKING, Component.translatable("feed.account.tag.checking"));
        AccountManager.INSTANCE.api().checkTag(tag).whenComplete((r, ex) -> Minecraft.getInstance().execute(() -> {
            if (gen != generation) {
                return; // user kept typing
            }
            if (ex != null) {
                set(State.ERROR, AccountUi.describe(AccountApi.unwrap(ex)));
            } else if (r.available() && r.claim()) {
                claim = true;
                set(State.FREE, Component.translatable("feed.account.tag.claim", "@" + r.tag()));
            } else if (r.available()) {
                set(State.FREE, Component.translatable("feed.account.tag.free", "@" + r.tag()));
            } else if (r.isProtected()) {
                set(State.TAKEN, Component.translatable("feed.account.tag.protected"));
            } else if (r.reason() != null && !r.reason().isBlank() && !r.reason().contains("занят") && !r.reason().equals("Tag is already taken")) {
                set(State.TAKEN, Component.literal(r.reason()));
            } else {
                set(State.TAKEN, Component.translatable("feed.account.tag.taken"));
            }
        }));
    }

    /** Server rejected the tag on submit (race with another player). */
    public void markTaken() {
        set(State.TAKEN, Component.translatable("feed.account.tag.taken"));
    }

    private void set(State s, Component m) {
        state = s;
        message = m;
    }

    public State state() {
        return state;
    }

    /** Server said: free for you because it is your verified Discord handle. */
    public boolean isClaim() {
        return claim;
    }

    public String value() {
        return value;
    }

    public Component message() {
        return message;
    }

    /** Submit allowed: free, or still checking/unknown (server decides). */
    public boolean submittable() {
        return state == State.FREE || state == State.WAITING || state == State.CHECKING || state == State.ERROR;
    }

    public int color() {
        return switch (state) {
            case FREE -> AccountUi.GREEN;
            case INVALID, TAKEN -> RetroUi.ERROR;
            case OWN, ERROR -> RetroUi.GRAY;
            default -> RetroUi.GRAY;
        };
    }
}
