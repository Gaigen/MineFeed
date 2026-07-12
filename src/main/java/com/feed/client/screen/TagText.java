package com.feed.client.screen;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class TagText {

    private static final Pattern TAG_PATTERN = Pattern.compile("#[\\p{L}\\p{N}_]+");

    public record Seg(String text, boolean tag) {}

    private TagText() {
    }

    public static List<Seg> segments(String line) {
        List<Seg> out = new ArrayList<>();
        Matcher m = TAG_PATTERN.matcher(line);
        int last = 0;
        while (m.find()) {
            if (m.start() > last) {
                out.add(new Seg(line.substring(last, m.start()), false));
            }
            out.add(new Seg(m.group(), true));
            last = m.end();
        }
        if (last < line.length()) {
            out.add(new Seg(line.substring(last), false));
        }
        return out;
    }
}
