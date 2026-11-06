package com.feed.client.screen;

import net.minecraft.client.gui.GuiGraphics;

import java.util.HashMap;
import java.util.Map;

/** Tiny 7x7 pixel icons for the sidebar and buttons (drawn with fills, recolored freely). */
public final class PixelIcon {

    public static final int SIZE = 7;
    private static final Map<String, String[]> ICONS = new HashMap<>();

    static {
        ICONS.put("home", new String[]{"...#...", "..###..", ".#####.", "#######", ".#...#.", ".#.#.#.", ".#.#.#."});
        ICONS.put("bell", new String[]{"...#...", "..###..", ".#####.", ".#####.", ".#####.", "#######", "...#..."});
        ICONS.put("user", new String[]{"..###..", "..###..", "..###..", ".......", ".#####.", "#######", "#######"});
        ICONS.put("palette", new String[]{".#####.", "#.#####", "###.###", "#######", "##.....", ".#.....", "..#####"});
        ICONS.put("lock", new String[]{"..###..", ".#...#.", ".#...#.", "#######", "###.###", "###.###", "#######"});
        ICONS.put("chat", new String[]{"#######", "#.....#", "#.....#", "#.....#", "#######", ".##....", ".#....."});
        ICONS.put("key", new String[]{".###...", "#...#..", "#...###", "#...#.#", ".###...", ".......", "......."});
        ICONS.put("plus", new String[]{"...#...", "...#...", "...#...", "#######", "...#...", "...#...", "...#..."});
        ICONS.put("globe", new String[]{"..###..", ".#.#.#.", "#..#..#", "#######", "#..#..#", ".#.#.#.", "..###.."});
        ICONS.put("img", new String[]{"#######", "#.....#", "#.#...#", "#...#.#", "#..####", "#.#####", "#######"});
        ICONS.put("crown", new String[]{"#..#..#", "##.#.##", "#######", "#######", "#######", ".......", "#######"});
        ICONS.put("search", new String[]{".###...", "#...#..", "#...#..", "#...#..", ".####..", "....##.", ".....##"});
        ICONS.put("discord", new String[]{".#...#.", "#######", "#######", "##.#.##", "#######", "#######", ".#...#."});
        ICONS.put("clock", new String[]{"..###..", ".#.#.#.", "#..#..#", "#..##.#", "#.....#", ".#...#.", "..###.."});
        ICONS.put("gear", new String[]{"..#.#..", ".#####.", "##...##", ".#...#.", "##...##", ".#####.", "..#.#.."});
        ICONS.put("back", new String[]{"...#...", "..#....", ".#.....", "#######", ".#.....", "..#....", "...#..."});
        ICONS.put("shield", new String[]{"#######", "#######", "##.#.##", "###.###", ".#####.", "..###..", "...#..."});
        ICONS.put("flag", new String[]{"######.", "#######", "######.", "#......", "#......", "#......", "#......"});
        ICONS.put("pin", new String[]{"..###..", ".#####.", "..###..", "..###..", "#######", "...#...", "...#..."});
        ICONS.put("pos", new String[]{"..###..", ".#...#.", ".#.#.#.", ".#...#.", "..#.#..", "..#.#..", "...#..."});
        ICONS.put("poll", new String[]{"#......", "#####..", "#......", "#######", "#......", "###....", "#......"});
        ICONS.put("edit", new String[]{".....##", "....###", "...###.", "..###..", ".###...", "##.....", "#......"});
        ICONS.put("fire", new String[]{"...#...", "..##...", "..###.#", ".#####.", "#######", "###.###", ".#####."});
        ICONS.put("eye", new String[]{".......", "..###..", ".#...#.", "#..#..#", ".#...#.", "..###..", "......."});
        ICONS.put("check", new String[]{".......", "......#", ".....#.", "#...#..", ".#.#...", "..#....", "......."});
    }

    private PixelIcon() {
    }

    public static boolean has(String name) {
        return ICONS.containsKey(name);
    }

    public static void draw(GuiGraphics g, String name, int x, int y, int color) {
        String[] rows = ICONS.get(name);
        if (rows == null) {
            return;
        }
        for (int j = 0; j < rows.length; j++) {
            String r = rows[j];
            int i = 0;
            while (i < r.length()) {
                if (r.charAt(i) != '#') {
                    i++;
                    continue;
                }
                int start = i;
                while (i < r.length() && r.charAt(i) == '#') {
                    i++;
                }
                g.fill(x + start, y + j, x + i, y + j + 1, color);
            }
        }
    }
}
