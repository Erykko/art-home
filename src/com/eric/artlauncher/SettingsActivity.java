package com.eric.artlauncher;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** Art Home settings, shown as a side panel over the artwork. */
public class SettingsActivity extends Activity {

    private static final int REQ_MEDIA = 7;
    private static final int DIALOG_THEME = android.R.style.Theme_DeviceDefault_Dialog_Alert;

    private Prefs prefs;
    private LinearLayout list;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = new Prefs(this);

        FrameLayout root = new FrameLayout(this);
        Style.frostedBackdrop(this, root, 0x55);

        ScrollView scroller = new ScrollView(this);
        scroller.setBackgroundColor(Style.withAlpha(Style.blend(Style.artColor, 0xFF000000, 0.6f), 0xD9));
        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(dp(32), dp(32), dp(32), dp(32));
        scroller.addView(list);
        root.addView(scroller, new FrameLayout.LayoutParams(dp(460),
                ViewGroup.LayoutParams.MATCH_PARENT, Gravity.END));

        setContentView(root);
        rebuild();
    }

    private int dp(float v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                getResources().getDisplayMetrics());
    }

    // ---------------------------------------------------------------- list

    private void rebuild() {
        View focused = getCurrentFocus();
        int focusIndex = focused == null ? -1 : list.indexOfChild(focused);
        list.removeAllViews();

        TextView title = new TextView(this);
        title.setText("Art Home settings");
        title.setTextColor(Color.WHITE);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 24);
        title.setPadding(dp(8), 0, 0, dp(20));
        list.addView(title);

        row("TV settings", "Picture, sound, network, accounts…", () -> {
            try {
                startActivity(new android.content.Intent(android.provider.Settings.ACTION_SETTINGS)
                        .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK));
            } catch (Exception ignored) {
            }
        });

        header("Layout");
        row("Home layout", Prefs.LAYOUT_LIST.equals(prefs.layout())
                ? "List: big app names on the left" : "Dock: frosted bar at the bottom", () -> {
            prefs.setLayout(Prefs.LAYOUT_LIST.equals(prefs.layout()) ? Prefs.LAYOUT_DOCK : Prefs.LAYOUT_LIST);
            rebuild();
        });

        header("Artwork");
        row("Sources", sourcesSummary(), this::chooseSources);
        if (prefs.sources().contains(Prefs.SOURCE_GPHOTOS)) {
            row("Google Photos album links",
                    prefs.albumUrls().isEmpty() ? "Not set" : prefs.albumUrls().size() + " album(s)",
                    this::editAlbumUrl);
            row("Photos per album", prefs.albumLimit() == 0 ? "All" : "Last " + prefs.albumLimit(),
                    this::chooseAlbumLimit);
            row("Refresh albums now", albumSummary(), this::refreshAlbumNow);
        }
        if (prefs.sources().contains(Prefs.SOURCE_MET) || prefs.sources().contains(Prefs.SOURCE_AIC)) {
            row("Download new museum art", "Replaces the museum packs with a fresh selection",
                    this::redownloadPacks);
        }
        row("Change artwork every", Prefs.DURATION_LABELS[indexOf(Prefs.DURATIONS_MS, prefs.artDurationMs())],
                this::chooseDuration);
        row("Order", prefs.shuffle() ? "Shuffle" : "In order", () -> {
            prefs.setShuffle(!prefs.shuffle());
            rebuild();
        });
        row("Favourites only", (prefs.favouritesOnly() ? "On" : "Off")
                + " (" + prefs.favourites().size() + " favourites)", () -> {
            prefs.setFavouritesOnly(!prefs.favouritesOnly());
            rebuild();
        });
        if (!prefs.favourites().isEmpty()) {
            row("Clear favourites", prefs.favourites().size() + " favourites", () -> {
                prefs.clearFavourites();
                prefs.setFavouritesOnly(false);
                rebuild();
            });
        }
        if (!prefs.blocked().isEmpty()) {
            row("Show hidden artworks again", prefs.blocked().size() + " hidden", () -> {
                prefs.clearBlocked();
                rebuild();
            });
        }

        header("Display");
        row("Frame", frameLabel(prefs.frameStyle()), this::chooseFrame);
        row("Portrait photos", portraitLabel(prefs.portraitMode()), this::choosePortraitMode);
        row("Slow pan and zoom", prefs.kenBurns() ? "On" : "Off", () -> {
            prefs.setKenBurns(!prefs.kenBurns());
            rebuild();
        });
        row("Night mode", prefs.nightMode()
                ? String.format("Dim %02d:00–%02d:00", prefs.nightStartHour(), prefs.nightEndHour()) : "Off",
                this::chooseNight);
        row("Clock", prefs.showClock() ? "Shown" : "Hidden", () -> {
            prefs.setShowClock(!prefs.showClock());
            rebuild();
        });
        row("Artwork title", prefs.showTitle() ? "Shown" : "Hidden", () -> {
            prefs.setShowTitle(!prefs.showTitle());
            rebuild();
        });
        row("Hide apps when idle", Prefs.IDLE_LABELS[indexOf(Prefs.IDLE_MS, prefs.idleHideMs())],
                this::chooseIdle);

        header("Weather");
        row("Weather on home screen", prefs.showWeather() ? "Shown" : "Hidden", () -> {
            prefs.setShowWeather(!prefs.showWeather());
            rebuild();
        });
        if (prefs.showWeather()) {
            row("Weather location", prefs.weatherCity().isEmpty()
                    ? "Automatic (estimated from your internet connection)" : prefs.weatherCity(),
                    this::editWeatherCity);
            row("Temperature", prefs.fahrenheit() ? "Fahrenheit (°F)" : "Celsius (°C)", () -> {
                prefs.setFahrenheit(!prefs.fahrenheit());
                rebuild();
            });
        }

        header("Apps");
        row("Inputs in All Apps", prefs.showInputs() ? "Shown" : "Hidden", () -> {
            prefs.setShowInputs(!prefs.showInputs());
            rebuild();
        });
        row("Recently used in All Apps", prefs.showContinueWatching() ? "Shown" : "Hidden", () -> {
            prefs.setShowContinueWatching(!prefs.showContinueWatching());
            rebuild();
        });
        row("Hidden apps", prefs.hiddenApps().isEmpty() ? "None" : prefs.hiddenApps().size() + " hidden",
                this::chooseHiddenApps);
        row("Reset home apps", "Long-press OK on an app to move or remove it", () -> {
            prefs.setAppOrder(new ArrayList<>());
            Toast.makeText(this, "Top row reset", Toast.LENGTH_SHORT).show();
        });

        header("Profiles");
        row("Show Art Home on", profilesSummary(), this::chooseProfiles);
        row("Current profile", prefs.manualProfile().isEmpty()
                ? "Automatic (" + (prefs.detectedProfile().isEmpty() ? "unknown" : prefs.detectedProfile()) + ")"
                : prefs.manualProfile() + " (set manually)", this::chooseManualProfile);
        row("Skip \"Who's watching?\" at startup", prefs.skipProfileChooser()
                ? "On: uses the last profile" : "Off", () -> {
            prefs.setSkipProfileChooser(!prefs.skipProfileChooser());
            rebuild();
        });
        info("Kids profiles always use the Google TV kids home.");

        header("Adding your own pictures");
        row("Add photos from your phone", "Shows a QR code; pick photos on your phone",
                () -> startActivity(new android.content.Intent(this, UploadActivity.class)));
        info("Or copy photos into a folder named \"ArtHome\" (or Pictures/ArtHome) on a USB drive "
                + "plugged into the TV, then turn on \"My pictures\" in Sources.");

        header("About");
        row("Update Art Home", "Version " + versionName() + " · send a new APK from your phone",
                () -> startActivity(new android.content.Intent(this, UploadActivity.class)));

        Style.font(list);
        if (focusIndex >= 0 && focusIndex < list.getChildCount()
                && list.getChildAt(focusIndex).isFocusable()) {
            list.getChildAt(focusIndex).requestFocus();
        } else {
            for (int i = 0; i < list.getChildCount(); i++) {
                if (list.getChildAt(i).isFocusable()) {
                    list.getChildAt(i).requestFocus();
                    break;
                }
            }
        }
    }

    @Override
    public void finish() {
        super.finish();
        Style.exit(this);
    }

    private void header(String text) {
        TextView h = new TextView(this);
        h.setText(text.toUpperCase());
        h.setTextColor(0xFF9AA0A6);
        h.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        h.setLetterSpacing(0.08f);
        h.setPadding(dp(8), dp(18), 0, dp(6));
        list.addView(h);
    }

    private void info(String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextColor(0xFFB0B4BA);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        t.setPadding(dp(8), dp(4), dp(8), dp(8));
        list.addView(t);
    }

    private void row(String label, String value, Runnable onClick) {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.VERTICAL);
        r.setPadding(dp(16), dp(12), dp(16), dp(12));
        r.setFocusable(true);
        r.setFocusableInTouchMode(true);

        final GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(12));
        bg.setColor(Color.TRANSPARENT);
        r.setBackground(bg);

        final TextView l = new TextView(this);
        l.setText(label);
        l.setTextColor(Color.WHITE);
        l.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        final TextView v = new TextView(this);
        v.setText(value);
        v.setTextColor(0xFFB0B4BA);
        v.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        v.setSingleLine(true);
        v.setEllipsize(android.text.TextUtils.TruncateAt.END);
        r.addView(l);
        r.addView(v);

        r.setOnFocusChangeListener((view, hasFocus) -> {
            bg.setColor(hasFocus ? Color.WHITE : Color.TRANSPARENT);
            l.setTextColor(hasFocus ? 0xFF111111 : Color.WHITE);
            v.setTextColor(hasFocus ? 0xFF444444 : 0xFFB0B4BA);
        });
        r.setOnClickListener(view -> onClick.run());

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(4);
        list.addView(r, lp);
    }

    private static int indexOf(long[] arr, long v) {
        for (int i = 0; i < arr.length; i++) if (arr[i] == v) return i;
        return 0;
    }

    // ---------------------------------------------------------------- summaries

    private String sourcesSummary() {
        Set<String> s = prefs.sources();
        List<String> parts = new ArrayList<>();
        if (s.contains(Prefs.SOURCE_NXTFRAME)) parts.add("NXTFRAME art (" + count(ArtSources.nxtframeDir(this)) + ")");
        if (s.contains(Prefs.SOURCE_MET)) parts.add("The Met (" + count(ArtPacks.dir(this, Prefs.SOURCE_MET)) + ")");
        if (s.contains(Prefs.SOURCE_AIC)) parts.add("Art Institute (" + count(ArtPacks.dir(this, Prefs.SOURCE_AIC)) + ")");
        if (s.contains(Prefs.SOURCE_GPHOTOS)) parts.add("Google Photos (" + count(ArtSources.albumDir(this)) + ")");
        if (s.contains(Prefs.SOURCE_UPLOADS)) parts.add("From phone (" + count(ArtSources.uploadsDir(this)) + ")");
        if (s.contains(Prefs.SOURCE_LOCAL)) {
            int n = 0;
            for (File d : ArtSources.localDirs()) n += count(d);
            parts.add("My pictures (" + n + ")");
        }
        return parts.isEmpty() ? "None" : String.join(", ", parts);
    }

    private static int count(File dir) {
        if (dir == null) return 0;
        File[] f = dir.listFiles((d, n) -> n.matches("(?i).*\\.(jpe?g|png|webp)$"));
        return f == null ? 0 : f.length;
    }

    private String albumSummary() {
        long t = prefs.albumFetchedAt();
        if (t == 0) return "Never downloaded";
        long mins = (System.currentTimeMillis() - t) / 60_000;
        return count(ArtSources.albumDir(this)) + " photos, updated "
                + (mins < 1 ? "just now" : mins < 60 ? mins + " min ago" : (mins / 60) + " h ago")
                + " (refreshes every 6 h)";
    }

    private String profilesSummary() {
        Set<String> known = prefs.knownProfiles();
        if (known.isEmpty()) return "All profiles";
        Set<String> off = prefs.disabledProfiles();
        List<String> on = new ArrayList<>();
        for (String p : known) if (!off.contains(p)) on.add(p);
        if (on.size() == known.size()) return "All profiles";
        return on.isEmpty() ? "No profiles" : String.join(", ", on);
    }

    // ---------------------------------------------------------------- choosers

    private void chooseSources() {
        final String[] keys = {Prefs.SOURCE_NXTFRAME, Prefs.SOURCE_MET, Prefs.SOURCE_AIC,
                Prefs.SOURCE_GPHOTOS, Prefs.SOURCE_UPLOADS, Prefs.SOURCE_LOCAL};
        final String[] labels = {"TCL NXTFRAME art", "The Met (public domain paintings)",
                "Art Institute of Chicago (public domain)", "Google Photos shared albums",
                "Photos sent from your phone", "My pictures (USB / Pictures folder)"};
        final Set<String> chosen = prefs.sources();
        boolean[] checked = new boolean[keys.length];
        for (int i = 0; i < keys.length; i++) checked[i] = chosen.contains(keys[i]);

        new AlertDialog.Builder(this, DIALOG_THEME)
                .setTitle("Artwork sources")
                .setMultiChoiceItems(labels, checked, (d, which, isChecked) -> {
                    if (isChecked) chosen.add(keys[which]); else chosen.remove(keys[which]);
                })
                .setPositiveButton("Done", (d, w) -> {
                    if (chosen.isEmpty()) chosen.add(Prefs.SOURCE_NXTFRAME);
                    prefs.setSources(chosen);
                    if (chosen.contains(Prefs.SOURCE_LOCAL)) ensureMediaPermission();
                    for (String pack : new String[]{Prefs.SOURCE_MET, Prefs.SOURCE_AIC}) {
                        if (chosen.contains(pack) && ArtPacks.isEmpty(this, pack)) downloadPack(pack);
                    }
                    if (chosen.contains(Prefs.SOURCE_GPHOTOS) && prefs.albumUrls().isEmpty()) {
                        rebuild();
                        editAlbumUrl();
                        return;
                    }
                    rebuild();
                })
                .show();
    }

    private void chooseDuration() {
        new AlertDialog.Builder(this, DIALOG_THEME)
                .setTitle("Change artwork every")
                .setSingleChoiceItems(Prefs.DURATION_LABELS, indexOf(Prefs.DURATIONS_MS, prefs.artDurationMs()),
                        (d, which) -> {
                            prefs.setArtDurationMs(Prefs.DURATIONS_MS[which]);
                            d.dismiss();
                            rebuild();
                        })
                .show();
    }

    private void chooseIdle() {
        new AlertDialog.Builder(this, DIALOG_THEME)
                .setTitle("Hide apps when idle")
                .setSingleChoiceItems(Prefs.IDLE_LABELS, indexOf(Prefs.IDLE_MS, prefs.idleHideMs()),
                        (d, which) -> {
                            prefs.setIdleHideMs(Prefs.IDLE_MS[which]);
                            d.dismiss();
                            rebuild();
                        })
                .show();
    }

    private void chooseProfiles() {
        final List<String> known = new ArrayList<>(prefs.knownProfiles());
        if (known.isEmpty()) {
            new AlertDialog.Builder(this, DIALOG_THEME)
                    .setTitle("No profiles found yet")
                    .setMessage("Open the profile switcher (person icon on Art Home) once so Art Home "
                            + "can learn the profile names, then come back here.")
                    .setPositiveButton("OK", null)
                    .show();
            return;
        }
        final Set<String> off = prefs.disabledProfiles();
        boolean[] checked = new boolean[known.size()];
        for (int i = 0; i < known.size(); i++) checked[i] = !off.contains(known.get(i));

        new AlertDialog.Builder(this, DIALOG_THEME)
                .setTitle("Show Art Home on")
                .setMultiChoiceItems(known.toArray(new String[0]), checked, (d, which, isChecked) -> {
                    if (isChecked) off.remove(known.get(which)); else off.add(known.get(which));
                })
                .setPositiveButton("Done", (d, w) -> {
                    prefs.setDisabledProfiles(off);
                    rebuild();
                })
                .show();
    }

    private void editAlbumUrl() {
        final EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI
                | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        input.setHint("https://photos.app.goo.gl/…");
        input.setText(prefs.albumUrl());
        input.setMinLines(2);
        FrameLayout box = new FrameLayout(this);
        box.setPadding(dp(24), dp(8), dp(24), 0);
        box.addView(input);

        new AlertDialog.Builder(this, DIALOG_THEME)
                .setTitle("Google Photos album links")
                .setMessage("In Google Photos on your phone: open an album → Share → Create link. "
                        + "Type one link per line.")
                .setView(box)
                .setPositiveButton("Save", (d, w) -> {
                    prefs.setAlbumUrl(input.getText().toString());
                    prefs.setAlbumFetchedAt(0);
                    rebuild();
                    if (!prefs.albumUrls().isEmpty()) refreshAlbumNow();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void refreshAlbumNow() {
        if (prefs.albumUrls().isEmpty()) {
            editAlbumUrl();
            return;
        }
        Toast.makeText(this, "Downloading album…", Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            String msg;
            try {
                msg = "Album updated: " + ArtSources.refreshAlbum(this, prefs) + " photos";
            } catch (Exception e) {
                msg = "Couldn't load album: " + e.getMessage();
            }
            final String m = msg;
            runOnUiThread(() -> {
                Toast.makeText(this, m, Toast.LENGTH_LONG).show();
                rebuild();
            });
        }).start();
    }

    private void chooseAlbumLimit() {
        final int[] values = {0, 25, 50, 100};
        final String[] labels = {"All photos", "Last 25", "Last 50", "Last 100"};
        int sel = 0;
        for (int i = 0; i < values.length; i++) if (values[i] == prefs.albumLimit()) sel = i;
        new AlertDialog.Builder(this, DIALOG_THEME)
                .setTitle("Photos per album")
                .setSingleChoiceItems(labels, sel, (d, which) -> {
                    prefs.setAlbumLimit(values[which]);
                    prefs.setAlbumFetchedAt(0);
                    d.dismiss();
                    rebuild();
                })
                .show();
    }

    private static String portraitLabel(String mode) {
        if (Prefs.PORTRAIT_PAIR.equals(mode)) return "Two side by side";
        if (Prefs.PORTRAIT_CAROUSEL.equals(mode)) return "Carousel of four (glides every change)";
        return "One, over a blurred backdrop";
    }

    private void choosePortraitMode() {
        final String[] keys = {Prefs.PORTRAIT_SINGLE, Prefs.PORTRAIT_PAIR, Prefs.PORTRAIT_CAROUSEL};
        final String[] labels = {portraitLabel(keys[0]), portraitLabel(keys[1]), portraitLabel(keys[2])};
        int sel = java.util.Arrays.asList(keys).indexOf(prefs.portraitMode());
        new AlertDialog.Builder(this, DIALOG_THEME)
                .setTitle("Portrait photos")
                .setSingleChoiceItems(labels, Math.max(0, sel), (d, which) -> {
                    prefs.setPortraitMode(keys[which]);
                    d.dismiss();
                    rebuild();
                })
                .show();
    }

    private static String frameLabel(String style) {
        if (Prefs.FRAME_MAT.equals(style)) return "White mat";
        if (Prefs.FRAME_GALLERY.equals(style)) return "Gallery frame";
        return "None (full screen)";
    }

    private void chooseFrame() {
        final String[] keys = {Prefs.FRAME_NONE, Prefs.FRAME_MAT, Prefs.FRAME_GALLERY};
        final String[] labels = {frameLabel(keys[0]), frameLabel(keys[1]), frameLabel(keys[2])};
        int sel = java.util.Arrays.asList(keys).indexOf(prefs.frameStyle());
        new AlertDialog.Builder(this, DIALOG_THEME)
                .setTitle("Frame")
                .setSingleChoiceItems(labels, Math.max(0, sel), (d, which) -> {
                    prefs.setFrameStyle(keys[which]);
                    d.dismiss();
                    rebuild();
                })
                .show();
    }

    private void chooseNight() {
        final int[][] hours = {{-1, -1}, {22, 6}, {21, 7}, {23, 7}, {0, 6}};
        final String[] labels = {"Off", "Dim 22:00–06:00", "Dim 21:00–07:00", "Dim 23:00–07:00", "Dim 00:00–06:00"};
        int sel = 0;
        if (prefs.nightMode()) {
            for (int i = 1; i < hours.length; i++) {
                if (hours[i][0] == prefs.nightStartHour() && hours[i][1] == prefs.nightEndHour()) sel = i;
            }
        }
        new AlertDialog.Builder(this, DIALOG_THEME)
                .setTitle("Night mode")
                .setSingleChoiceItems(labels, sel, (d, which) -> {
                    prefs.setNightMode(which != 0);
                    if (which != 0) prefs.setNightHours(hours[which][0], hours[which][1]);
                    d.dismiss();
                    rebuild();
                })
                .show();
    }

    private void chooseHiddenApps() {
        final List<String> hidden = new ArrayList<>(prefs.hiddenApps());
        if (hidden.isEmpty()) {
            Toast.makeText(this, "No hidden apps. Long-press OK on a tile to hide it.", Toast.LENGTH_LONG).show();
            return;
        }
        PackageManager pm = getPackageManager();
        String[] labels = new String[hidden.size()];
        boolean[] checked = new boolean[hidden.size()];
        for (int i = 0; i < hidden.size(); i++) {
            try {
                labels[i] = String.valueOf(pm.getApplicationLabel(pm.getApplicationInfo(hidden.get(i), 0)));
            } catch (PackageManager.NameNotFoundException e) {
                labels[i] = hidden.get(i);
            }
            checked[i] = true;
        }
        final Set<String> keep = prefs.hiddenApps();
        new AlertDialog.Builder(this, DIALOG_THEME)
                .setTitle("Hidden apps (untick to show again)")
                .setMultiChoiceItems(labels, checked, (d, which, isChecked) -> {
                    if (isChecked) keep.add(hidden.get(which)); else keep.remove(hidden.get(which));
                })
                .setPositiveButton("Done", (d, w) -> {
                    prefs.setHiddenApps(keep);
                    rebuild();
                })
                .show();
    }

    private void chooseManualProfile() {
        final List<String> options = new ArrayList<>();
        options.add("Automatic");
        options.addAll(prefs.knownProfiles());
        int sel = Math.max(0, options.indexOf(prefs.manualProfile()));
        new AlertDialog.Builder(this, DIALOG_THEME)
                .setTitle("Current profile")
                .setSingleChoiceItems(options.toArray(new String[0]), sel, (d, which) -> {
                    prefs.setManualProfile(which == 0 ? "" : options.get(which));
                    d.dismiss();
                    rebuild();
                })
                .show();
    }

    private void downloadPack(final String pack) {
        final String name = Prefs.SOURCE_MET.equals(pack) ? "The Met" : "Art Institute of Chicago";
        Toast.makeText(this, "Downloading " + name + " art…", Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            String msg;
            try {
                msg = name + ": " + ArtPacks.download(this, pack, null) + " artworks ready";
            } catch (Exception e) {
                msg = "Couldn't download " + name + " art: " + e.getMessage();
            }
            final String m = msg;
            runOnUiThread(() -> {
                Toast.makeText(this, m, Toast.LENGTH_LONG).show();
                rebuild();
            });
        }).start();
    }

    private void redownloadPacks() {
        for (String pack : new String[]{Prefs.SOURCE_MET, Prefs.SOURCE_AIC}) {
            if (prefs.sources().contains(pack)) downloadPack(pack);
        }
    }

    private String versionName() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (PackageManager.NameNotFoundException e) {
            return "?";
        }
    }

    private void editWeatherCity() {
        final EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setHint("e.g. Nairobi (leave empty for automatic)");
        input.setText(prefs.weatherCity());
        FrameLayout box = new FrameLayout(this);
        box.setPadding(dp(24), dp(8), dp(24), 0);
        box.addView(input);
        new AlertDialog.Builder(this, DIALOG_THEME)
                .setTitle("Weather location")
                .setView(box)
                .setPositiveButton("Save", (d, w) -> {
                    prefs.setWeatherCity(input.getText().toString());
                    rebuild();
                })
                .setNeutralButton("Automatic", (d, w) -> {
                    prefs.setWeatherCity("");
                    rebuild();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void ensureMediaPermission() {
        String perm = Build.VERSION.SDK_INT >= 33
                ? Manifest.permission.READ_MEDIA_IMAGES : Manifest.permission.READ_EXTERNAL_STORAGE;
        // Media location lets Art Home read where USB photos were taken (Android hides GPS otherwise).
        String loc = Manifest.permission.ACCESS_MEDIA_LOCATION;
        if (checkSelfPermission(perm) != PackageManager.PERMISSION_GRANTED
                || checkSelfPermission(loc) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{perm, loc}, REQ_MEDIA);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        rebuild();
    }
}
