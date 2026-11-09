package com.feed.client.account;

import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/** Mirror of server/src/names.js so the UI can answer instantly. The server stays the source of truth. */
public final class TagRules {

    public static final int MIN = 2;
    public static final int MAX = 32;
    public static final int NICK_MAX = 32;

    private static final Pattern TAG = Pattern.compile("^[a-z0-9_.]{2,32}$");
    private static final Set<String> RESERVED = Set.of(
            "admin", "administrator", "mod", "moderator", "moderators", "minefeed", "support", "system",
            "staff", "official", "owner", "root", "server", "help", "everyone", "here", "null", "undefined",
            "discord", "mojang", "minecraft", "notch");

    public enum Problem { NONE, LENGTH, CHARS, DOUBLE_DOT, RESERVED }

    private TagRules() {
    }

    public static String normalize(String raw) {
        if (raw == null) {
            return "";
        }
        String s = raw.trim();
        if (s.startsWith("@")) {
            s = s.substring(1);
        }
        return s.toLowerCase(Locale.ROOT);
    }

    public static Problem check(String normalized) {
        if (normalized.length() < MIN || normalized.length() > MAX) {
            return Problem.LENGTH;
        }
        if (!TAG.matcher(normalized).matches()) {
            return Problem.CHARS;
        }
        if (normalized.contains("..")) {
            return Problem.DOUBLE_DOT;
        }
        if (RESERVED.contains(normalized) || normalized.startsWith("minefeed") || normalized.startsWith("admin")) {
            return Problem.RESERVED;
        }
        return Problem.NONE;
    }

    /** Best-effort tag suggestion from a Minecraft username (may still be taken). */
    public static String suggestFrom(String name) {
        String s = normalize(name).replaceAll("[^a-z0-9_.]", "").replaceAll("\\.{2,}", ".");
        if (s.length() > MAX) {
            s = s.substring(0, MAX);
        }
        if (s.length() < MIN || check(s) != Problem.NONE) {
            return "";
        }
        return s;
    }

    /** Characters allowed while typing (uppercase is lowercased on insert). */
    public static boolean allowedWhileTyping(String s) {
        return s.matches("[A-Za-z0-9_.]*");
    }

    /** Server rule: control, §, zero-width and bidi characters are removed; whitespace collapsed. */
    public static String cleanNick(String raw) {
        if (raw == null) {
            return "";
        }
        String s = raw.replaceAll("\u00a7.", "").replaceAll("[\\u0000-\\u001f\\u007f-\\u009f\\u00a7\\u200b-\\u200f\\u2028-\\u202e\\u2060-\\u206f\\ufeff]", "");
        return s.replaceAll("\\s+", " ").trim();
    }

    public static boolean validNick(String cleaned) {
        int len = cleaned.codePointCount(0, cleaned.length());
        return len >= 1 && len <= NICK_MAX;
    }
}
