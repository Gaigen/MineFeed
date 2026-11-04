package com.feed.client;

import java.util.Map;

/** Expands known placeholders once; substituted user text is never interpreted as a template. */
public final class MessageTemplate {
    private MessageTemplate() {}

    public static String render(String template, Map<String, String> values) {
        if (template == null) return "";
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < template.length();) {
            if (template.charAt(i) == '{') {
                int end = template.indexOf('}', i + 1);
                if (end >= 0) {
                    String key = template.substring(i + 1, end);
                    if (values.containsKey(key)) {
                        String value = values.get(key);
                        if (value != null) result.append(value);
                        i = end + 1;
                        continue;
                    }
                }
            }
            result.append(template.charAt(i++));
        }
        return result.toString();
    }
}
