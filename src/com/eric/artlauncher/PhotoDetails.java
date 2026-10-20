package com.eric.artlauncher;

import android.content.Context;
import android.location.Address;
import android.location.Geocoder;
import android.media.ExifInterface;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Reads a phone photo's EXIF (date taken, camera, GPS) and turns the GPS position into a
 * place name, for the Art Home info chip and card.
 */
public final class PhotoDetails {

    public String date = "";     // e.g. "12 March 2026, 14:05"
    public String camera = "";   // e.g. "Google Pixel 8"
    public double[] latLong;     // null when the photo has no location
    public String place = "";    // e.g. "Gardens by the Bay, Singapore"

    private static final Map<String, String> PLACE_CACHE = new HashMap<>();
    private static long lastNominatimCall = 0;

    private PhotoDetails() {
    }

    /** Must be called on the original file, before it is re-encoded (which drops EXIF). */
    public static PhotoDetails read(String path) {
        PhotoDetails d = new PhotoDetails();
        try {
            ExifInterface exif = new ExifInterface(path);
            String raw = exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL);
            if (raw == null) raw = exif.getAttribute(ExifInterface.TAG_DATETIME);
            if (raw != null) {
                java.util.Date parsed = new SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US).parse(raw);
                if (parsed != null) d.date = new SimpleDateFormat("d MMMM yyyy, HH:mm", Locale.getDefault()).format(parsed);
            }
            String make = exif.getAttribute(ExifInterface.TAG_MAKE);
            String model = exif.getAttribute(ExifInterface.TAG_MODEL);
            if (model != null) {
                model = model.trim();
                d.camera = make != null && !model.toLowerCase().startsWith(make.trim().toLowerCase())
                        ? make.trim() + " " + model : model;
            }
            float[] ll = new float[2];
            if (exif.getLatLong(ll) && !(ll[0] == 0 && ll[1] == 0)) d.latLong = new double[]{ll[0], ll[1]};
        } catch (Exception ignored) {
        }
        return d;
    }

    /** Blocking network lookup; call from a background thread. */
    public void resolvePlace(Context c) {
        if (latLong == null) return;
        String key = String.format(Locale.US, "%.3f,%.3f", latLong[0], latLong[1]);
        synchronized (PLACE_CACHE) {
            if (PLACE_CACHE.containsKey(key)) {
                place = PLACE_CACHE.get(key);
                return;
            }
        }
        // OpenStreetMap names landmarks ("OCBC Skyway, Marina South"); the TV's own geocoder
        // often only knows the city or country, so it is the fallback.
        place = fromNominatim();
        if (place.isEmpty()) place = fromGeocoder(c);
        synchronized (PLACE_CACHE) {
            PLACE_CACHE.put(key, place);
        }
    }

    private String fromGeocoder(Context c) {
        try {
            if (!Geocoder.isPresent()) return "";
            List<Address> list = new Geocoder(c, Locale.getDefault()).getFromLocation(latLong[0], latLong[1], 1);
            if (list == null || list.isEmpty()) return "";
            Address a = list.get(0);
            List<String> parts = new ArrayList<>();
            addDistinct(parts, a.getFeatureName() != null && !a.getFeatureName().matches("[0-9\\-]+")
                    ? a.getFeatureName() : null);
            addDistinct(parts, a.getLocality() != null ? a.getLocality() : a.getSubAdminArea());
            addDistinct(parts, a.getCountryName());
            return String.join(", ", parts);
        } catch (Exception e) {
            return "";
        }
    }

    /** OpenStreetMap's free reverse geocoder (max 1 request per second, needs a User-Agent). */
    private String fromNominatim() {
        try {
            synchronized (PhotoDetails.class) {
                long wait = 1100 - (System.currentTimeMillis() - lastNominatimCall);
                if (wait > 0) Thread.sleep(wait);
                lastNominatimCall = System.currentTimeMillis();
            }
            String url = String.format(Locale.US,
                    "https://nominatim.openstreetmap.org/reverse?format=jsonv2&zoom=17&lat=%.6f&lon=%.6f",
                    latLong[0], latLong[1]);
            HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setConnectTimeout(10_000);
            conn.setReadTimeout(15_000);
            conn.setRequestProperty("User-Agent", "ArtHome/3 (personal TV photo frame)");
            conn.setRequestProperty("Accept-Language", Locale.getDefault().toLanguageTag());
            String body;
            try (InputStream in = conn.getInputStream()) {
                ByteArrayOutputStream buf = new ByteArrayOutputStream();
                byte[] b = new byte[8192];
                int n;
                while ((n = in.read(b)) > 0) buf.write(b, 0, n);
                body = buf.toString("UTF-8");
            } finally {
                conn.disconnect();
            }
            JSONObject json = new JSONObject(body);
            JSONObject addr = json.optJSONObject("address");
            List<String> parts = new ArrayList<>();
            addDistinct(parts, json.optString("name", null));
            if (addr != null) {
                addDistinct(parts, firstOf(addr, "neighbourhood", "suburb", "quarter"));
                addDistinct(parts, firstOf(addr, "city", "town", "village", "county", "state"));
                addDistinct(parts, addr.optString("country", null));
            }
            // Keep it short enough for the on-screen chip.
            return String.join(", ", parts.subList(0, Math.min(3, parts.size())));
        } catch (Exception e) {
            return "";
        }
    }

    private static String firstOf(JSONObject o, String... keys) {
        for (String k : keys) {
            String v = o.optString(k, "");
            if (!v.isEmpty()) return v;
        }
        return null;
    }

    private static void addDistinct(List<String> parts, String s) {
        if (s == null || s.trim().isEmpty()) return;
        for (String p : parts) if (p.equalsIgnoreCase(s.trim())) return;
        parts.add(s.trim());
    }
}
