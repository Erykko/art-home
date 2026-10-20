package com.eric.artlauncher;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Free public-domain art packs downloaded from museum open-access APIs:
 * The Metropolitan Museum of Art and the Art Institute of Chicago. Only landscape
 * images are kept, re-encoded at TV resolution.
 */
public final class ArtPacks {

    public interface Progress {
        void update(int done, int target);
    }

    private static final int PACK_SIZE = 60;
    private static final String UA = "ArtHome/2 (personal TV art frame)";

    private ArtPacks() {
    }

    public static File dir(Context c, String source) {
        return c.getExternalFilesDir("pack_" + source);
    }

    public static boolean isEmpty(Context c, String source) {
        File[] f = dir(c, source).listFiles((d, n) -> n.endsWith(".jpg"));
        return f == null || f.length == 0;
    }

    /** Blocking; run on a background thread. Returns the number of images in the pack. */
    public static int download(Context c, String source, Progress progress) throws Exception {
        File dir = dir(c, source);
        File[] old = dir.listFiles();
        if (old != null) for (File f : old) f.delete();
        int got = Prefs.SOURCE_MET.equals(source) ? downloadMet(dir, progress) : downloadAic(dir, progress);
        if (got == 0) throw new Exception("No images downloaded");
        return got;
    }

    // ---------------------------------------------------------------- The Met

    private static int downloadMet(File dir, Progress progress) throws Exception {
        List<Integer> ids = new ArrayList<>();
        for (String q : new String[]{"landscape", "seascape", "garden"}) {
            JSONObject res = new JSONObject(fetchText(
                    // v1/search was retired 2026-10-01; v1.1 is paginated (limit/offset).
                    "https://collectionapi.metmuseum.org/public/collection/v1.1/search?isHighlight=true"
                            + "&hasImages=true&medium=Paintings&limit=100&q=" + q));
            JSONArray arr = res.optJSONArray("objectIDs");
            if (arr != null) for (int i = 0; i < arr.length(); i++) {
                if (!ids.contains(arr.getInt(i))) ids.add(arr.getInt(i));
            }
        }
        Collections.shuffle(ids);
        int got = 0;
        for (int id : ids) {
            if (got >= PACK_SIZE) break;
            try {
                JSONObject o = new JSONObject(fetchText(
                        "https://collectionapi.metmuseum.org/public/collection/v1/objects/" + id));
                if (!o.optBoolean("isPublicDomain")) continue;
                String img = o.optString("primaryImage", "");
                if (img.isEmpty()) continue;
                String base = "met" + id;
                if (saveLandscape(img, new File(dir, base + ".jpg"))) {
                    ArtMeta.append(dir, base, o.optString("title"), o.optString("artistDisplayName"),
                            joinNonEmpty(o.optString("objectDate"), o.optString("medium"),
                                    "The Metropolitan Museum of Art"));
                    got++;
                    if (progress != null) progress.update(got, PACK_SIZE);
                }
            } catch (Exception ignored) {
                // Skip objects that fail; there are plenty more.
            }
        }
        return got;
    }

    // ---------------------------------------------------------------- Art Institute of Chicago

    private static int downloadAic(File dir, Progress progress) throws Exception {
        List<JSONObject> works = new ArrayList<>();
        for (String q : new String[]{"landscape", "impressionism", "sea"}) {
            // Public-domain paintings only (artwork_type_id 1 = Painting), as an Elasticsearch query.
            JSONObject params = new JSONObject()
                    .put("q", q)
                    .put("limit", 100)
                    .put("fields", new JSONArray(Arrays.asList("id", "title", "artist_title",
                            "date_display", "medium_display", "image_id", "thumbnail")))
                    .put("query", new JSONObject().put("bool", new JSONObject().put("must", new JSONArray()
                            .put(new JSONObject().put("term", new JSONObject().put("is_public_domain", true)))
                            .put(new JSONObject().put("term", new JSONObject().put("artwork_type_id", 1))))));
            String url = "https://api.artic.edu/api/v1/artworks/search?params="
                    + URLEncoder.encode(params.toString(), "UTF-8");
            JSONArray data = new JSONObject(fetchText(url)).optJSONArray("data");
            if (data != null) for (int i = 0; i < data.length(); i++) works.add(data.getJSONObject(i));
        }
        Collections.shuffle(works);
        int got = 0;
        List<String> seen = new ArrayList<>();
        for (JSONObject w : works) {
            if (got >= PACK_SIZE) break;
            String imageId = w.optString("image_id", "");
            if (imageId.isEmpty() || "null".equals(imageId) || seen.contains(imageId)) continue;
            seen.add(imageId);
            JSONObject thumb = w.optJSONObject("thumbnail");
            if (thumb != null && thumb.optInt("width") < thumb.optInt("height") * 1.2) continue;
            try {
                String base = "aic" + w.optInt("id");
                String img = "https://www.artic.edu/iiif/2/" + imageId + "/full/1686,/0/default.jpg";
                if (saveLandscape(img, new File(dir, base + ".jpg"))) {
                    ArtMeta.append(dir, base, w.optString("title"), w.optString("artist_title"),
                            joinNonEmpty(w.optString("date_display"), w.optString("medium_display"),
                                    "Art Institute of Chicago"));
                    got++;
                    if (progress != null) progress.update(got, PACK_SIZE);
                }
            } catch (Exception ignored) {
            }
        }
        return got;
    }

    // ---------------------------------------------------------------- helpers

    private static String joinNonEmpty(String... parts) {
        StringBuilder b = new StringBuilder();
        for (String p : parts) {
            if (p == null || p.isEmpty() || "null".equals(p)) continue;
            if (b.length() > 0) b.append(" · ");
            b.append(p);
        }
        return b.toString();
    }

    /** Downloads an image, keeps it only if landscape, and re-encodes it at most 1920 px wide. */
    private static boolean saveLandscape(String url, File dest) throws Exception {
        byte[] data = fetchBytes(url);
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(data, 0, data.length, o);
        if (o.outWidth <= 0 || o.outWidth < o.outHeight * 1.2) return false;
        int sample = 1;
        while (o.outWidth / (sample * 2) >= 1920) sample *= 2;
        BitmapFactory.Options d = new BitmapFactory.Options();
        d.inSampleSize = sample;
        Bitmap bmp = BitmapFactory.decodeByteArray(data, 0, data.length, d);
        if (bmp == null) return false;
        if (bmp.getWidth() > 1920) {
            Bitmap scaled = Bitmap.createScaledBitmap(bmp, 1920,
                    Math.round(bmp.getHeight() * 1920f / bmp.getWidth()), true);
            bmp.recycle();
            bmp = scaled;
        }
        try (FileOutputStream out = new FileOutputStream(dest)) {
            bmp.compress(Bitmap.CompressFormat.JPEG, 88, out);
        }
        bmp.recycle();
        return true;
    }

    private static HttpURLConnection open(String url) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setConnectTimeout(15_000);
        conn.setReadTimeout(30_000);
        conn.setRequestProperty("User-Agent", UA);
        conn.setRequestProperty("AIC-User-Agent", UA);
        return conn;
    }

    private static byte[] fetchBytes(String url) throws Exception {
        HttpURLConnection conn = open(url);
        try (InputStream in = conn.getInputStream()) {
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            byte[] b = new byte[32768];
            int n;
            while ((n = in.read(b)) > 0) buf.write(b, 0, n);
            return buf.toByteArray();
        } finally {
            conn.disconnect();
        }
    }

    private static String fetchText(String url) throws Exception {
        return new String(fetchBytes(url), "UTF-8");
    }
}
