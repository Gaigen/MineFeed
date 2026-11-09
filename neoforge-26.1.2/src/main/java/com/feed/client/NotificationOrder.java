package com.feed.client;

import java.util.Comparator;

/** Snowflake IDs are unsigned decimal strings; compare without overflowing a long. */
public final class NotificationOrder {
    private NotificationOrder() {}

    public static int compare(String a, String b) {
        if (a == null) return b == null ? 0 : -1;
        if (b == null) return 1;
        int length = Integer.compare(a.length(), b.length());
        return length != 0 ? length : a.compareTo(b);
    }

    public static String initialCursor(String latest) {
        return latest == null || latest.isEmpty() ? "0" : latest;
    }
}
