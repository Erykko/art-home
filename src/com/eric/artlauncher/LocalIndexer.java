package com.eric.artlauncher;

import android.content.Context;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Reads photo details (date, camera, place) for pictures in the USB / Pictures "ArtHome"
 * folders once, and caches them in Art Home's own storage keyed by file path.
 */
public final class LocalIndexer {

    private static volatile boolean running = false;

    private LocalIndexer() {
    }

    public static File indexFile(Context c) {
        return new File(c.getExternalFilesDir(null), "local_meta.txt");
    }

    /** Blocking; run on a background thread. Returns true if anything new was indexed. */
    public static boolean run(Context c) {
        if (running) return false;
        running = true;
        try {
            File index = indexFile(c);
            Set<String> done = new HashSet<>();
            if (index.exists()) {
                try (BufferedReader r = new BufferedReader(new FileReader(index))) {
                    String line;
                    while ((line = r.readLine()) != null) {
                        int bar = line.indexOf('|');
                        if (bar > 0) done.add(line.substring(0, bar));
                    }
                }
            }
            List<File> todo = new ArrayList<>();
            for (File dir : ArtSources.localDirs()) {
                File[] files = dir.listFiles();
                if (files == null) continue;
                for (File f : files) {
                    String n = f.getName().toLowerCase();
                    if (!(n.endsWith(".jpg") || n.endsWith(".jpeg") || n.endsWith(".png") || n.endsWith(".webp"))) continue;
                    if (!done.contains(key(f))) todo.add(f);
                }
            }
            for (File f : todo) {
                PhotoDetails d = PhotoDetails.read(f.getPath());
                d.resolvePlace(c);
                String name = f.getName().replaceAll("\\.[^.]*$", "");
                String title = !d.place.isEmpty() ? d.place : !d.date.isEmpty() ? d.date : name;
                String subtitle = !d.place.isEmpty() ? d.date : "";
                List<String> extra = new ArrayList<>();
                if (!d.camera.isEmpty()) extra.add(d.camera);
                extra.add(name);
                try (FileWriter w = new FileWriter(index, true)) {
                    w.write(key(f) + "|" + clean(title) + "|" + clean(subtitle) + "|"
                            + clean(String.join(" · ", extra)) + "\n");
                }
            }
            return !todo.isEmpty();
        } catch (Exception e) {
            return false;
        } finally {
            running = false;
        }
    }

    private static String key(File f) {
        String p = f.getAbsolutePath();
        int dot = p.lastIndexOf('.');
        return dot > 0 ? p.substring(0, dot) : p;
    }

    private static String clean(String s) {
        return s == null ? "" : s.replace('|', ' ').replace('\n', ' ').trim();
    }
}
