package com.feed.client.screen;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Splits text into plain parts, @mentions, #hashtags, [x y z] and ||spoilers|| (same rules as the server and Discord). */
public final class TagText {

    private static final Pattern TAG_PATTERN = Pattern.compile(
            "@[A-Za-z0-9_.]{2,32}|(?<![\\p{L}\\p{N}_&#/])#[\\p{L}\\p{N}_]{2,32}(?![\\p{L}\\p{N}_])"
                    + "|\\[-?\\d{1,8} -?\\d{1,4} -?\\d{1,8}(?: (?:nether|end))?]",
            Pattern.UNICODE_CHARACTER_CLASS);

    private static final Pattern SPOILER = Pattern.compile("\\|\\|(.+?)\\|\\|");
    private static final String MARK = "||";

    public enum Kind { TEXT, MENTION, HASHTAG, COORDS, SPOILER }

    public record Seg(String text, Kind kind) {
        public boolean tag() {
            return kind != Kind.TEXT;
        }
    }

    private TagText() {
    }

    /** Top level: spoilers (their text without the "||") and the rest split into tags. */
    public static List<Seg> segments(String line) {
        if (!line.contains(MARK)) {
            return tagSegments(line);
        }
        List<Seg> out = new ArrayList<>();
        Matcher m = SPOILER.matcher(line);
        int last = 0;
        while (m.find()) {
            if (m.start() > last) {
                out.addAll(tagSegments(line.substring(last, m.start())));
            }
            out.add(new Seg(m.group(1), Kind.SPOILER));
            last = m.end();
        }
        if (last < line.length()) {
            out.addAll(tagSegments(line.substring(last)));
        }
        return out;
    }

    /** Text with every ||spoiler|| replaced by a bar, for one-line previews. */
    public static String hideSpoilers(String text) {
        return text == null || !text.contains(MARK) ? text : SPOILER.matcher(text).replaceAll("\u2592\u2592\u2592");
    }

    public static boolean hasSpoiler(String text) {
        return text != null && text.contains(MARK) && SPOILER.matcher(text.replace('\n', ' ')).find();
    }

    /**
     * After word wrapping a spoiler may run over several lines: close it at the end of a line and open it again
     * on the next one, so every line can be drawn on its own.
     */
    public static List<String> closeSpoilers(List<String> lines) {
        boolean any = false;
        for (String l : lines) {
            if (l.contains(MARK)) {
                any = true;
                break;
            }
        }
        if (!any) {
            return lines;
        }
        List<String> out = new ArrayList<>(lines.size());
        boolean open = false;
        for (String l : lines) {
            String s = open ? MARK + l : l;
            int marks = 0;
            for (int i = s.indexOf(MARK); i >= 0; i = s.indexOf(MARK, i + 2)) {
                marks++;
            }
            open = marks % 2 == 1;
            out.add(open ? s + MARK : s);
        }
        return out;
    }

    public static List<Seg> tagSegments(String line) {
        List<Seg> out = new ArrayList<>();
        Matcher m = TAG_PATTERN.matcher(line);
        int last = 0;
        while (m.find()) {
            String g = m.group();
            Kind kind = g.startsWith("@") ? Kind.MENTION : g.startsWith("[") ? Kind.COORDS : Kind.HASHTAG;
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

    /** Coordinates as written in posts: "[x y z]" or "[x y z nether|end]". */
    public static String coords(int x, int y, int z, String dimension) {
        String d = "minecraft:the_nether".equals(dimension) ? " nether" : "minecraft:the_end".equals(dimension) ? " end" : "";
        return "[" + x + " " + y + " " + z + d + "]";
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
