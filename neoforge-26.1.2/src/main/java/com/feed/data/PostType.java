package com.feed.data;

import java.util.List;

public enum PostType {
    RIP("rip", "[F]", 0xFFFF5555, "heart", "coffin"),
    HELP("help", "[?]", 0xFFFFFF55, "ring", "nerd"),
    GG("gg", "[GG]", 0xFF55FF55, "cup", "clover"),
    TALK("talk", "[~]", 0xFF55FFFF, "fire", "zzz");

    public final String id;
    public final String prefix;
    private final int baseAccent;
    /** Theme adaptation of the 8-bit colors (set by the client theme). */
    public static java.util.function.IntUnaryOperator tint = c -> c;
    public final String reactionA;
    public final String reactionB;

    PostType(String id, String prefix, int accent, String reactionA, String reactionB) {
        this.id = id;
        this.prefix = prefix;
        this.baseAccent = accent;
        this.reactionA = reactionA;
        this.reactionB = reactionB;
    }

    public int accent() {
        return tint.applyAsInt(baseAccent);
    }

    public List<String> reactions() {
        return List.of(reactionA, reactionB);
    }

    public String composeTitleKey() {
        return "feed.screen.compose.title." + id;
    }

    public String emptyKey() {
        return "feed.screen.feed.empty." + id;
    }

    public static PostType byId(String id) {
        if (id != null) {
            for (PostType t : values()) {
                if (t.id.equalsIgnoreCase(id)) {
                    return t;
                }
            }
        }
        return TALK;
    }
}