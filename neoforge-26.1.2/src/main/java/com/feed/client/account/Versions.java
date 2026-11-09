package com.feed.client.account;

/** Compares dotted versions like 0.2.0, 0.10.1, 1.0.0-beta (suffix ignored). */
public final class Versions {

    private Versions() {
    }

    public static int compare(String a, String b) {
        int[] x = parts(a);
        int[] y = parts(b);
        for (int i = 0; i < Math.max(x.length, y.length); i++) {
            int p = i < x.length ? x[i] : 0;
            int q = i < y.length ? y[i] : 0;
            if (p != q) {
                return Integer.compare(p, q);
            }
        }
        return 0;
    }

    private static int[] parts(String v) {
        if (v == null || v.isBlank()) {
            return new int[0];
        }
        String core = v.trim();
        int cut = core.indexOf('-');
        if (cut >= 0) {
            core = core.substring(0, cut);
        }
        cut = core.indexOf('+');
        if (cut >= 0) {
            core = core.substring(0, cut);
        }
        String[] raw = core.split("\\.");
        int[] out = new int[raw.length];
        for (int i = 0; i < raw.length; i++) {
            try {
                out[i] = Integer.parseInt(raw[i].replaceAll("[^0-9]", ""));
            } catch (NumberFormatException e) {
                out[i] = 0;
            }
        }
        return out;
    }
}
