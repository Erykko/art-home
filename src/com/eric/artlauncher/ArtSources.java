package com.eric.artlauncher;

import android.content.Context;
import android.os.Environment;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Finds artwork files for each enabled source and keeps the Google Photos album cached. */
public final class ArtSources {

    private static final long ALBUM_REFRESH_MS = 6 * 60 * 60 * 1000L;
    private static final int ALBUM_MAX_PHOTOS = 300;
    private static final Pattern PHOTO_URL =
            Pattern.compile("https://lh3\\.googleusercontent\\.com/pw/[A-Za-z0-9_\\-]+");

    private ArtSources() {
    }

    public static File nxtframeDir(Context c) {
        return c.getExternalFilesDir("art");
    }

    public static File uploadsDir(Context c) {
        return c.getExternalFilesDir("uploads");
    }

    public static File albumDir(Context c) {
        return c.getExternalFilesDir("gphotos");
    }

    /** "Pictures/ArtHome" on internal storage and on every attached USB drive. */
    public static List<File> localDirs() {
        List<File> dirs = new ArrayList<>();
        dirs.add(new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "ArtHome"));
        File[] volumes = new File("/storage").listFiles();
        if (volumes != null) {
            for (File v : volumes) {
                String n = v.getName();
                if (n.equals("emulated") || n.equals("self")) continue;
                dirs.add(new File(v, "ArtHome"));
                dirs.add(new File(v, "Pictures/ArtHome"));
            }
        }
        return dirs;
    }

    public static List<File> collect(Context c, Prefs prefs) {
        Set<String> sources = prefs.sources();
        List<File> out = new ArrayList<>();
        if (sources.contains(Prefs.SOURCE_NXTFRAME)) addImages(nxtframeDir(c), out);
        if (sources.contains(Prefs.SOURCE_GPHOTOS)) addImages(albumDir(c), out);
        if (sources.contains(Prefs.SOURCE_LOCAL)) for (File d : localDirs()) addImages(d, out);
        if (sources.contains(Prefs.SOURCE_UPLOADS)) addImages(uploadsDir(c), out);
        if (sources.contains(Prefs.SOURCE_MET)) addImages(ArtPacks.dir(c, Prefs.SOURCE_MET), out);
        if (sources.contains(Prefs.SOURCE_AIC)) addImages(ArtPacks.dir(c, Prefs.SOURCE_AIC), out);
        Collections.sort(out, (a, b) -> a.getAbsolutePath().compareTo(b.getAbsolutePath()));

        Set<String> blocked = prefs.blocked();
        Set<String> favs = prefs.favourites();
        List<File> filtered = new ArrayList<>();
        for (File f : out) {
            if (blocked.contains(f.getAbsolutePath())) continue;
            if (prefs.favouritesOnly() && !favs.contains(f.getAbsolutePath())) continue;
            filtered.add(f);
        }
        // Never end up with nothing to show because of filters.
        return filtered.isEmpty() ? out : filtered;
    }

    private static void addImages(File dir, List<File> out) {
        if (dir == null) return;
        File[] files = dir.listFiles();
        if (files == null) return;
        for (File f : files) {
            String n = f.getName().toLowerCase();
            if (n.endsWith(".jpg") || n.endsWith(".jpeg") || n.endsWith(".png") || n.endsWith(".webp")) {
                out.add(f);
            }
        }
    }

    public static boolean albumNeedsRefresh(Prefs prefs) {
        return prefs.sources().contains(Prefs.SOURCE_GPHOTOS)
                && !prefs.albumUrls().isEmpty()
                && System.currentTimeMillis() - prefs.albumFetchedAt() > ALBUM_REFRESH_MS;
    }

    /**
     * Downloads every photo of each public Google Photos shared-album link at TV resolution.
     * Blocking; call from a background thread. Returns the number of photos now cached.
     */
    public static int refreshAlbum(Context c, Prefs prefs) throws Exception {
        Set<String> urls = new LinkedHashSet<>();
        int limit = prefs.albumLimit();
        for (String album : prefs.albumUrls()) {
            List<String> found = new ArrayList<>();
            Matcher m = PHOTO_URL.matcher(fetchText(album));
            while (m.find()) if (!found.contains(m.group())) found.add(m.group());
            // Shared albums usually list photos oldest first (album sort order), so keep the tail.
            if (limit > 0 && found.size() > limit) found = found.subList(found.size() - limit, found.size());
            for (String u : found) if (urls.size() < ALBUM_MAX_PHOTOS) urls.add(u);
        }
        if (urls.isEmpty()) throw new Exception("No photos found. Are the album links shared publicly?");

        File dir = albumDir(c);
        Set<String> keep = new LinkedHashSet<>();
        for (String u : urls) {
            String name = "p" + Integer.toHexString(u.hashCode()) + ".jpg";
            keep.add(name);
            File f = new File(dir, name);
            if (f.exists() && f.length() > 0) continue;
            download(u + "=w1920-h1080", f);
        }
        File[] existing = dir.listFiles();
        if (existing != null) for (File f : existing) if (!keep.contains(f.getName())) f.delete();
        prefs.setAlbumFetchedAt(System.currentTimeMillis());
        return keep.size();
    }

    private static HttpURLConnection open(String url) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setInstanceFollowRedirects(true);
        conn.setConnectTimeout(15_000);
        conn.setReadTimeout(30_000);
        conn.setRequestProperty("User-Agent",
                "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126 Safari/537.36");
        return conn;
    }

    private static String fetchText(String url) throws Exception {
        // Short links (photos.app.goo.gl) redirect across hosts, which HttpURLConnection
        // will not follow automatically, so follow up to 5 hops by hand.
        for (int hop = 0; hop < 5; hop++) {
            HttpURLConnection conn = open(url);
            int code = conn.getResponseCode();
            if (code >= 300 && code < 400 && conn.getHeaderField("Location") != null) {
                url = new URL(new URL(url), conn.getHeaderField("Location")).toString();
                conn.disconnect();
                continue;
            }
            try (InputStream in = conn.getInputStream()) {
                ByteArrayOutputStream buf = new ByteArrayOutputStream();
                byte[] b = new byte[16384];
                int n;
                while ((n = in.read(b)) > 0) buf.write(b, 0, n);
                return buf.toString("UTF-8");
            } finally {
                conn.disconnect();
            }
        }
        throw new Exception("Too many redirects");
    }

    private static void download(String url, File dest) throws Exception {
        File tmp = new File(dest.getPath() + ".part");
        HttpURLConnection conn = open(url);
        try (InputStream in = conn.getInputStream(); OutputStream out = new FileOutputStream(tmp)) {
            byte[] b = new byte[16384];
            int n;
            while ((n = in.read(b)) > 0) out.write(b, 0, n);
        } finally {
            conn.disconnect();
        }
        if (!tmp.renameTo(dest)) tmp.delete();
    }
}
