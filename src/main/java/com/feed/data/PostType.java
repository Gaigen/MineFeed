package com.feed.data;

import java.util.List;

public enum PostType {
    RIP("rip", "[F]", 0xFFFF5555, "heart", "coffin"),
    HELP("help", "[?]", 0xFFFFFF55, "ring", "nerd"),
    GG("gg", "[GG]", 0xFF55FF55, "cup", "clover"),
    TALK("talk", "[~]", 0xFF55FFFF, "fire", "zzz");

    public final String id;
    public final String prefix;
    public final int accent;
    public final String reactionA;
    public final String reactionB;

    PostType(String id, String prefix, int accent, String reactionA, String reactionB) {
        this.id = id;
        this.prefix = prefix;
        this.accent = accent;
        this.reactionA = reactionA;
        this.reactionB = reactionB;
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