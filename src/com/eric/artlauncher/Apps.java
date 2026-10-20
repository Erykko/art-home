package com.eric.artlauncher;

import android.app.Activity;
import android.app.AppOpsManager;
import android.app.usage.UsageStats;
import android.app.usage.UsageStatsManager;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.drawable.Drawable;
import android.media.tv.TvContract;
import android.media.tv.TvInputInfo;
import android.media.tv.TvInputManager;
import android.os.Process;
import android.os.SystemClock;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** App, input and "recently used" lists shared by the home screen and the All Apps screen. */
public final class Apps {

    public static final String GOOGLE_TV = "com.google.android.apps.tv.launcherx";
    public static final int DOCK_MAX = 8;
    public static final int DOCK_DEFAULT = 5;

    /** Default top row until the user changes it. */
    private static final List<String> DEFAULT_ORDER = Arrays.asList(
            "com.google.android.youtube.tv",
            "com.stremio.one",
            "com.netflix.ninja",
            "com.spotify.tv.android");

    public static class Entry {
        public String key;       // package name (or input id)
        public String label;
        public Intent launch;
        public Drawable banner;
        public Drawable icon;
        public boolean isApp = true;
    }

    private Apps() {
    }

    /** Every launchable TV app (except Art Home and hidden apps), alphabetical. */
    public static List<Entry> all(Context c, Prefs prefs) {
        PackageManager pm = c.getPackageManager();
        Intent q = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER);
        Set<String> hidden = prefs.hiddenApps();
        Map<String, Entry> byPkg = new HashMap<>();
        for (ResolveInfo ri : pm.queryIntentActivities(q, 0)) {
            String pkg = ri.activityInfo.packageName;
            if (pkg.equals(c.getPackageName()) || hidden.contains(pkg) || byPkg.containsKey(pkg)) continue;
            Entry e = new Entry();
            e.key = pkg;
            e.label = String.valueOf(ri.loadLabel(pm));
            e.launch = new Intent(Intent.ACTION_MAIN)
                    .addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER)
                    .setComponent(new ComponentName(pkg, ri.activityInfo.name))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
            e.banner = ri.activityInfo.loadBanner(pm);
            if (e.banner == null) e.banner = ri.activityInfo.applicationInfo.loadBanner(pm);
            e.icon = ri.loadIcon(pm);
            byPkg.put(pkg, e);
        }
        Entry gtv = googleTv(c);
        if (gtv != null && !hidden.contains(GOOGLE_TV)) byPkg.put(GOOGLE_TV, gtv);
        List<Entry> out = new ArrayList<>(byPkg.values());
        Collections.sort(out, (a, b) -> a.label.compareToIgnoreCase(b.label));
        return out;
    }

    /** The top row: the user's chosen apps, or the defaults. */
    public static List<Entry> dock(Context c, Prefs prefs) {
        List<Entry> all = all(c, prefs);
        Map<String, Entry> byPkg = new HashMap<>();
        for (Entry e : all) byPkg.put(e.key, e);
        List<String> keys = dockKeys(c, prefs, all);
        List<Entry> out = new ArrayList<>();
        for (String k : keys) if (byPkg.containsKey(k)) out.add(byPkg.get(k));
        return out;
    }

    public static List<String> dockKeys(Context c, Prefs prefs, List<Entry> all) {
        List<String> chosen = prefs.appOrder();
        // Older versions saved the order of every app; keep only the first few as the top row.
        if (chosen.size() > DOCK_MAX) return new ArrayList<>(chosen.subList(0, DOCK_DEFAULT));
        if (!chosen.isEmpty()) return chosen;
        List<String> keys = new ArrayList<>();
        Set<String> available = new java.util.HashSet<>();
        for (Entry e : all) available.add(e.key);
        for (String k : DEFAULT_ORDER) if (available.contains(k) && keys.size() < DOCK_DEFAULT) keys.add(k);
        for (Entry e : all) {
            if (keys.size() >= DOCK_DEFAULT) break;
            if (!keys.contains(e.key) && !GOOGLE_TV.equals(e.key)) keys.add(e.key);
        }
        return keys;
    }

    private static Entry googleTv(Context c) {
        Entry e = new Entry();
        e.key = GOOGLE_TV;
        e.label = "Google TV";
        e.launch = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
                .setPackage(GOOGLE_TV).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            e.icon = c.getPackageManager().getApplicationIcon(GOOGLE_TV);
        } catch (PackageManager.NameNotFoundException ex) {
            return null;
        }
        return e;
    }

    /** HDMI inputs, in port order. */
    public static List<Entry> inputs(Context c) {
        List<Entry> out = new ArrayList<>();
        TvInputManager tim = (TvInputManager) c.getSystemService(Context.TV_INPUT_SERVICE);
        if (tim == null) return out;
        List<TvInputInfo> list = new ArrayList<>();
        for (TvInputInfo info : tim.getTvInputList()) {
            if (info.getType() == TvInputInfo.TYPE_HDMI && info.isPassthroughInput()) list.add(info);
        }
        Collections.sort(list, (a, b) -> a.getId().compareTo(b.getId()));
        for (TvInputInfo info : list) {
            Entry e = new Entry();
            e.isApp = false;
            e.key = info.getId();
            e.label = String.valueOf(info.loadLabel(c));
            e.launch = new Intent(Intent.ACTION_VIEW, TvContract.buildChannelUriForPassthroughInput(info.getId()))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            out.add(e);
        }
        return out;
    }

    /** Recently used apps (needs usage access), newest first. */
    public static List<Entry> recent(Context c, Prefs prefs, int max) {
        List<Entry> out = new ArrayList<>();
        AppOpsManager ops = (AppOpsManager) c.getSystemService(Context.APP_OPS_SERVICE);
        if (ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), c.getPackageName())
                != AppOpsManager.MODE_ALLOWED) {
            return out;
        }
        Map<String, Entry> byPkg = new HashMap<>();
        for (Entry e : all(c, prefs)) byPkg.put(e.key, e);
        UsageStatsManager usm = (UsageStatsManager) c.getSystemService(Context.USAGE_STATS_SERVICE);
        long now = System.currentTimeMillis();
        List<UsageStats> stats = usm.queryUsageStats(UsageStatsManager.INTERVAL_BEST, now - 7L * 86_400_000L, now);
        if (stats == null) return out;
        Collections.sort(stats, (a, b) -> Long.compare(b.getLastTimeUsed(), a.getLastTimeUsed()));
        List<String> seen = new ArrayList<>();
        for (UsageStats s : stats) {
            String pkg = s.getPackageName();
            if (seen.contains(pkg) || GOOGLE_TV.equals(pkg) || s.getTotalTimeInForeground() < 30_000) continue;
            seen.add(pkg);
            Entry e = byPkg.get(pkg);
            if (e != null) out.add(e);
            if (out.size() >= max) break;
        }
        return out;
    }

    public static void launch(Activity a, Entry e) {
        if (GOOGLE_TV.equals(e.key)) {
            HomeRedirectService.allowGoogleTvUntil = SystemClock.elapsedRealtime() + 5000;
        }
        try {
            a.startActivity(e.launch);
        } catch (ActivityNotFoundException | SecurityException ex) {
            Intent fallback = a.getPackageManager().getLaunchIntentForPackage(e.key);
            if (fallback != null) {
                try {
                    a.startActivity(fallback.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                } catch (Exception ignored) {
                }
            }
        }
    }
}
