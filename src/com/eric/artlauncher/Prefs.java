package com.eric.artlauncher;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** All Art Home settings, stored in one SharedPreferences file. */
public final class Prefs {

    public static final String SOURCE_NXTFRAME = "nxtframe";
    public static final String SOURCE_GPHOTOS = "gphotos";
    public static final String SOURCE_LOCAL = "local";
    public static final String SOURCE_UPLOADS = "uploads";
    public static final String SOURCE_MET = "met";
    public static final String SOURCE_AIC = "aic";

    public static final String FRAME_NONE = "none";
    public static final String FRAME_MAT = "mat";
    public static final String FRAME_GALLERY = "gallery";

    public static final long[] DURATIONS_MS = {15_000, 30_000, 60_000, 90_000, 300_000, 900_000, 3_600_000};
    public static final String[] DURATION_LABELS = {"15 seconds", "30 seconds", "1 minute", "90 seconds", "5 minutes", "15 minutes", "1 hour"};

    public static final long[] IDLE_MS = {0, 30_000, 120_000, 300_000};
    public static final String[] IDLE_LABELS = {"Never", "30 seconds", "2 minutes", "5 minutes"};

    private final SharedPreferences sp;

    public Prefs(Context c) {
        sp = c.getApplicationContext().getSharedPreferences("art_home", Context.MODE_PRIVATE);
    }

    public long artDurationMs() { return sp.getLong("art_duration", 90_000); }
    public void setArtDurationMs(long v) { sp.edit().putLong("art_duration", v).apply(); }

    public long idleHideMs() { return sp.getLong("idle_hide", 120_000); }
    public void setIdleHideMs(long v) { sp.edit().putLong("idle_hide", v).apply(); }

    public boolean shuffle() { return sp.getBoolean("shuffle", true); }
    public void setShuffle(boolean v) { sp.edit().putBoolean("shuffle", v).apply(); }

    public boolean showClock() { return sp.getBoolean("show_clock", true); }
    public void setShowClock(boolean v) { sp.edit().putBoolean("show_clock", v).apply(); }

    public boolean showTitle() { return sp.getBoolean("show_title", true); }
    public void setShowTitle(boolean v) { sp.edit().putBoolean("show_title", v).apply(); }

    public Set<String> sources() {
        return new HashSet<>(sp.getStringSet("sources", new HashSet<>(Arrays.asList(SOURCE_NXTFRAME))));
    }
    public void setSources(Set<String> v) { sp.edit().putStringSet("sources", new HashSet<>(v)).apply(); }

    /** Google Photos shared-album links, one per line. */
    public List<String> albumUrls() {
        List<String> out = new ArrayList<>();
        for (String s : sp.getString("album_url", "").split("\\s+")) if (!s.isEmpty()) out.add(s);
        return out;
    }
    public String albumUrl() { return String.join("\n", albumUrls()); }
    public void setAlbumUrl(String v) { sp.edit().putString("album_url", v.trim()).apply(); }

    /** 0 = all photos; otherwise keep only the last N photos of each album (newest are last). */
    public int albumLimit() { return sp.getInt("album_limit", 0); }
    public void setAlbumLimit(int v) { sp.edit().putInt("album_limit", v).apply(); }

    public long albumFetchedAt() { return sp.getLong("album_fetched_at", 0); }
    public void setAlbumFetchedAt(long v) { sp.edit().putLong("album_fetched_at", v).apply(); }

    // ---- art behaviour

    public boolean kenBurns() { return sp.getBoolean("ken_burns", true); }
    public void setKenBurns(boolean v) { sp.edit().putBoolean("ken_burns", v).apply(); }

    public String frameStyle() { return sp.getString("frame_style", FRAME_NONE); }
    public void setFrameStyle(String v) { sp.edit().putString("frame_style", v).apply(); }

    public boolean nightMode() { return sp.getBoolean("night_mode", false); }
    public void setNightMode(boolean v) { sp.edit().putBoolean("night_mode", v).apply(); }
    public int nightStartHour() { return sp.getInt("night_start", 22); }
    public int nightEndHour() { return sp.getInt("night_end", 6); }
    public void setNightHours(int start, int end) { sp.edit().putInt("night_start", start).putInt("night_end", end).apply(); }

    public boolean favouritesOnly() { return sp.getBoolean("favourites_only", false); }
    public void setFavouritesOnly(boolean v) { sp.edit().putBoolean("favourites_only", v).apply(); }

    public Set<String> favourites() { return new HashSet<>(sp.getStringSet("favourites", new HashSet<>())); }
    public void setFavourite(String path, boolean fav) {
        Set<String> s = favourites();
        if (fav) s.add(path); else s.remove(path);
        sp.edit().putStringSet("favourites", s).apply();
    }

    public void clearFavourites() { sp.edit().remove("favourites").apply(); }

    public Set<String> blocked() { return new HashSet<>(sp.getStringSet("blocked", new HashSet<>())); }
    public void block(String path) {
        Set<String> s = blocked();
        s.add(path);
        sp.edit().putStringSet("blocked", s).apply();
    }
    public void clearBlocked() { sp.edit().remove("blocked").apply(); }

    // ---- apps

    /** Package names in the user's chosen order (apps not listed follow alphabetically). */
    public List<String> appOrder() {
        List<String> out = new ArrayList<>();
        for (String s : sp.getString("app_order", "").split("\n")) if (!s.isEmpty()) out.add(s);
        return out;
    }
    public void setAppOrder(List<String> v) { sp.edit().putString("app_order", String.join("\n", v)).apply(); }

    public Set<String> hiddenApps() { return new HashSet<>(sp.getStringSet("hidden_apps", new HashSet<>())); }
    public void setHiddenApps(Set<String> v) { sp.edit().putStringSet("hidden_apps", new HashSet<>(v)).apply(); }

    public boolean showInputs() { return sp.getBoolean("show_inputs", true); }
    public void setShowInputs(boolean v) { sp.edit().putBoolean("show_inputs", v).apply(); }

    public boolean recentOnHome() { return sp.getBoolean("recent_on_home", true); }
    public void setRecentOnHome(boolean v) { sp.edit().putBoolean("recent_on_home", v).apply(); }

    public boolean showContinueWatching() { return sp.getBoolean("show_continue", true); }
    public void setShowContinueWatching(boolean v) { sp.edit().putBoolean("show_continue", v).apply(); }

    public static final String PORTRAIT_SINGLE = "single";
    public static final String PORTRAIT_PAIR = "pair";
    public static final String PORTRAIT_CAROUSEL = "carousel";

    /** How portrait photos are shown: one over a blurred backdrop, two side by side, or a carousel. */
    public String portraitMode() {
        String def = sp.getBoolean("portrait_pairs", false) ? PORTRAIT_PAIR : PORTRAIT_SINGLE;
        return sp.getString("portrait_mode", def);
    }
    public void setPortraitMode(String v) { sp.edit().putString("portrait_mode", v).apply(); }

    // ---- layout

    public static final String LAYOUT_DOCK = "dock";
    public static final String LAYOUT_LIST = "list";

    public String layout() { return sp.getString("layout", LAYOUT_DOCK); }
    public void setLayout(String v) { sp.edit().putString("layout", v).apply(); }

    // ---- weather

    public boolean showWeather() { return sp.getBoolean("show_weather", true); }
    public void setShowWeather(boolean v) { sp.edit().putBoolean("show_weather", v).apply(); }

    /** Empty = estimate location from the internet connection. */
    public String weatherCity() { return sp.getString("weather_city", ""); }
    public void setWeatherCity(String v) { sp.edit().putString("weather_city", v.trim()).apply(); }

    public boolean fahrenheit() { return sp.getBoolean("fahrenheit", false); }
    public void setFahrenheit(boolean v) { sp.edit().putBoolean("fahrenheit", v).apply(); }

    // ---- startup

    public boolean skipProfileChooser() { return sp.getBoolean("skip_chooser", false); }
    public void setSkipProfileChooser(boolean v) { sp.edit().putBoolean("skip_chooser", v).apply(); }

    /** Manually chosen profile; overrides automatic detection when set. */
    public String manualProfile() { return sp.getString("manual_profile", ""); }
    public void setManualProfile(String v) { sp.edit().putString("manual_profile", v).apply(); }

    // ---- profiles

    /** Profile names seen on Google TV's "Choose an account" screen, in display order. */
    public Set<String> knownProfiles() {
        String raw = sp.getString("known_profiles", "");
        Set<String> out = new LinkedHashSet<>();
        for (String s : raw.split("\n")) if (!s.isEmpty()) out.add(s);
        return out;
    }
    public void setKnownProfiles(Set<String> names) {
        sp.edit().putString("known_profiles", String.join("\n", names)).apply();
    }

    /** Profiles Art Home is turned off for. Stored as a deny-list so new profiles default to on. */
    public Set<String> disabledProfiles() {
        return new HashSet<>(sp.getStringSet("disabled_profiles", new HashSet<>()));
    }
    public void setDisabledProfiles(Set<String> v) { sp.edit().putStringSet("disabled_profiles", new HashSet<>(v)).apply(); }

    public String currentProfile() {
        String manual = manualProfile();
        return manual.isEmpty() ? detectedProfile() : manual;
    }
    public String detectedProfile() { return sp.getString("current_profile", ""); }
    public void setCurrentProfile(String v) { sp.edit().putString("current_profile", v).apply(); }

    public boolean enabledForCurrentProfile() {
        String p = currentProfile();
        return p.isEmpty() || !disabledProfiles().contains(p);
    }
}
