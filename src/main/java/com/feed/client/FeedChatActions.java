package com.feed.client;

/** Parsing and validation shared by local chat links and the client command. */
public final class FeedChatActions {
    public static final String COMMAND = "/minefeed_local";
    private FeedChatActions() {}

    public static boolean validPostId(String id) {
        return id != null && id.matches("[0-9]{1,25}");
    }

    public static Action parse(String input) {
        if (input == null) return null;
        String[] parts = input.trim().split("\\s+", 3);
        if (parts.length < 2 || !validPostId(parts[1])) return null;
        if (parts[0].equals("open") && parts.length == 2) return new Action("open", parts[1], "");
        if (parts[0].equals("reply") && parts.length == 3 && !parts[2].isBlank() && parts[2].length() <= 300)
            return new Action("reply", parts[1], parts[2].trim());
        return null;
    }

    public record Action(String kind, String postId, String text) {}
}
