package com.eric.artlauncher;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.util.HashMap;
import java.util.Map;

/**
 * Title / artist / description for artwork files. Each source folder may hold
 * "titles.txt" (NXTFRAME captures) or "meta.txt" (art packs), both with lines
 * "basename|title|artist|description".
 */
public final class ArtMeta {

    public static final class Info {
        public final String title, artist, description;

        Info(String title, String artist, String description) {
            this.title = title;
            this.artist = artist;
            this.description = description;
        }
    }

    private final Map<String, Info> byPath = new HashMap<>();

    public void clear() {
        byPath.clear();
    }

    public void loadDir(File dir) {
        if (dir == null) return;
        for (String name : new String[]{"titles.txt", "meta.txt"}) {
            File f = new File(dir, name);
            if (!f.exists()) continue;
            try (BufferedReader r = new BufferedReader(new FileReader(f))) {
                String line;
                while ((line = r.readLine()) != null) {
                    String[] p = line.split("\\|", -1);
                    if (p.length < 2 || p[1].startsWith("untitled_")) continue;
                    Info info = new Info(p[1], p.length > 2 ? p[2] : "", p.length > 3 ? p[3] : "");
                    byPath.put(new File(dir, p[0]).getAbsolutePath(), info);
                }
            } catch (Exception ignored) {
            }
        }
    }

    /** Loads a cache whose lines are keyed by full path without extension (USB pictures). */
    public void loadIndex(File index) {
        if (index == null || !index.exists()) return;
        try (BufferedReader r = new BufferedReader(new FileReader(index))) {
            String line;
            while ((line = r.readLine()) != null) {
                String[] p = line.split("\\|", -1);
                if (p.length < 2 || p[1].isEmpty()) continue;
                byPath.put(p[0], new Info(p[1], p.length > 2 ? p[2] : "", p.length > 3 ? p[3] : ""));
            }
        } catch (Exception ignored) {
        }
    }

    /** Looks up by file path without extension. */
    public Info get(File f) {
        String path = f.getAbsolutePath();
        int dot = path.lastIndexOf('.');
        return byPath.get(dot > 0 ? path.substring(0, dot) : path);
    }

    static synchronized void append(File dir, String base, String title, String artist, String description) {
        try (FileWriter w = new FileWriter(new File(dir, "meta.txt"), true)) {
            w.write(base + "|" + clean(title) + "|" + clean(artist) + "|" + clean(description) + "\n");
        } catch (Exception ignored) {
        }
    }

    private static String clean(String s) {
        return s == null ? "" : s.replace('|', ' ').replace('\n', ' ').trim();
    }
}
