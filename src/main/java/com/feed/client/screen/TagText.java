package com.feed.client.screen;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Splits text into plain parts, @mentions and #hashtags (same rules as the server). */
public final class TagText {

    private static final Pattern TAG_PATTERN = Pattern.compile(
            "@[A-Za-z0-9_.]{2,32}|(?<![\\p{L}\\p{N}_&#/])#[\\p{L}\\p{N}_]{2,32}(?![\\p{L}\\p{N}_])",
            Pattern.UNICODE_CHARACTER_CLASS);

    public enum Kind { TEXT, MENTION, HASHTAG }

    public record Seg(String text, Kind kind) {
        public boolean tag() {
            return kind != Kind.TEXT;
        }
    }

    private TagText() {
    }

    public static List<Seg> segments(String line) {
        List<Seg> out = new ArrayList<>();
        Matcher m = TAG_PATTERN.matcher(line);
        int last = 0;
        while (m.find()) {
            String g = m.group();
            Kind kind = g.startsWith("@") ? Kind.MENTION : Kind.HASHTAG;
            if (kind == Kind.HASHTAG && normalizeHashtag(g) == null) {
                continue;
            }
            if (m.start() > last) {
                out.add(new Seg(line.substring(last, m.start()), Kind.TEXT));
            }
            out.add(new Seg(g, kind));
            last = m.end();
        }
        if (last < line.length()) {
            out.add(new Seg(line.substring(last), Kind.TEXT));
        }
        return out;
    }

    /** "#Ёлка" -> "елка"; null if it is not a valid tag (needs a letter, 2-32 chars). */
    public static String normalizeHashtag(String raw) {
        if (raw == null) {
            return null;
        }
        String t = raw.trim();
        if (t.startsWith("#")) {
            t = t.substring(1);
        }
        t = t.toLowerCase(Locale.ROOT).replace('ё', 'е');
        if (t.length() < 2 || t.length() > 32) {
            return null;
        }
        boolean letter = false;
        for (int i = 0; i < t.length(); i++) {
            char c = t.charAt(i);
            if (Character.isLetter(c)) {
                letter = true;
            } else if (!Character.isDigit(c) && c != '_') {
                return null;
            }
        }
        return letter ? t : null;
    }
}
