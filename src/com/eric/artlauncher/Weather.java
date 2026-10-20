package com.eric.artlauncher;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.Locale;

/**
 * Current weather from Open-Meteo (free, no account). Location is either a city the user
 * typed (Open-Meteo geocoding) or an estimate from the internet connection (ipwho.is).
 */
public final class Weather {

    public static final long REFRESH_MS = 30 * 60 * 1000L;

    public double tempC;
    public int code;        // WMO weather code
    public boolean isDay;
    public String place = "";

    private Weather() {
    }

    /** Blocking; run on a background thread. */
    public static Weather fetch(Prefs prefs) throws Exception {
        double lat, lon;
        String place;
        String city = prefs.weatherCity();
        if (!city.isEmpty()) {
            JSONArray res = new JSONObject(get("https://geocoding-api.open-meteo.com/v1/search?count=1&name="
                    + URLEncoder.encode(city, "UTF-8"))).optJSONArray("results");
            if (res == null || res.length() == 0) throw new Exception("City not found: " + city);
            JSONObject r = res.getJSONObject(0);
            lat = r.getDouble("latitude");
            lon = r.getDouble("longitude");
            place = r.optString("name", city);
        } else {
            JSONObject ip = new JSONObject(get("https://ipwho.is/"));
            if (!ip.optBoolean("success")) throw new Exception("Couldn't estimate location");
            lat = ip.getDouble("latitude");
            lon = ip.getDouble("longitude");
            place = ip.optString("city", "");
        }
        JSONObject cur = new JSONObject(get(String.format(Locale.US,
                "https://api.open-meteo.com/v1/forecast?latitude=%.4f&longitude=%.4f"
                        + "&current=temperature_2m,weather_code,is_day&timezone=auto", lat, lon)))
                .getJSONObject("current");
        Weather w = new Weather();
        w.tempC = cur.getDouble("temperature_2m");
        w.code = cur.getInt("weather_code");
        w.isDay = cur.optInt("is_day", 1) == 1;
        w.place = place;
        return w;
    }

    public String temperature(boolean fahrenheit) {
        double t = fahrenheit ? tempC * 9 / 5 + 32 : tempC;
        return Math.round(t) + "°";
    }

    /** Short description for the WMO code. */
    public String description() {
        if (code == 0) return isDay ? "Sunny" : "Clear";
        if (code <= 2) return "Partly cloudy";
        if (code == 3) return "Cloudy";
        if (code == 45 || code == 48) return "Fog";
        if (code >= 51 && code <= 57) return "Drizzle";
        if ((code >= 61 && code <= 67) || (code >= 80 && code <= 82)) return "Rain";
        if ((code >= 71 && code <= 77) || code == 85 || code == 86) return "Snow";
        if (code >= 95) return "Thunderstorm";
        return "";
    }

    /** Icon kind for WeatherIcon. */
    public int kind() {
        if (code == 0) return isDay ? WeatherIcon.SUN : WeatherIcon.MOON;
        if (code <= 2) return isDay ? WeatherIcon.SUN_CLOUD : WeatherIcon.MOON_CLOUD;
        if (code == 3 || code == 45 || code == 48) return WeatherIcon.CLOUD;
        if (code >= 95) return WeatherIcon.STORM;
        if ((code >= 71 && code <= 77) || code == 85 || code == 86) return WeatherIcon.SNOW;
        return WeatherIcon.RAIN;
    }

    private static String get(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(10_000);
        c.setReadTimeout(15_000);
        c.setRequestProperty("User-Agent", "ArtHome/4 (personal TV)");
        try (InputStream in = c.getInputStream()) {
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) b.write(buf, 0, n);
            return b.toString("UTF-8");
        } finally {
            c.disconnect();
        }
    }
}
