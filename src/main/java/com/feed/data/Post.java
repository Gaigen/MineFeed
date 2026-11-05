package com.feed.data;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class Post {
    public String id;
    public String author;
    public String uuid;      // v2: author account id (null for Discord authors)
    public String authorTag; // v2: @tag without "@", null for Discord authors
    public boolean fromDiscord;
    public String avatar;    // "skin:<uuid>" | "discord:<id>/<hash>" | null
    public String text;
    public String type;
    public String createdAt;
    public int commentCount;
    public Map<String, Integer> reactions = new HashMap<>();
    public String myReaction;
    public List<String> tags;
    public boolean solved;
    public String space;    // "global" or community id
    public String imageUrl; // first picture (Discord CDN), null when none
    public int imageW;
    public int imageH;
    public boolean edited;
    public boolean pinned;
    /** Poll options, or null when the post has no poll. */
    public List<String> pollOptions;
    public int[] pollVotes;
    /** Index of my vote, -1 = not voted. */
    public int pollMy = -1;

    public Post() {
    }

    public boolean hasPoll() {
        return pollOptions != null && !pollOptions.isEmpty();
    }

    public int pollTotal() {
        int t = 0;
        if (pollVotes != null) {
            for (int v : pollVotes) {
                t += v;
            }
        }
        return t;
    }

    public PostType postType() {
        return PostType.byId(type);
    }

    public int reactionCount(String key) {
        return reactions.getOrDefault(key, 0);
    }

    public boolean mine(String key) {
        return key != null && key.equals(myReaction);
    }

    public String displayPrefix() {
        return solved ? "[✓]" : postType().prefix;
    }

    public int prefixColor() {
        return solved ? PostType.tint.applyAsInt(0xFF55FF55) : postType().accent();
    }
}
