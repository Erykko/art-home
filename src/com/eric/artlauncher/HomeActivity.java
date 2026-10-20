package com.eric.artlauncher;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.AppOpsManager;
import android.app.usage.UsageStats;
import android.app.usage.UsageStatsManager;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.media.tv.TvContract;
import android.media.tv.TvInputInfo;
import android.media.tv.TvInputManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.os.SystemClock;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextClock;
import android.widget.TextView;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/** Home screen: artwork full screen, apps and inputs on top. */
public class HomeActivity extends Activity {

    private static final long FADE_MS = 1500;

    /** Default app order until the user rearranges tiles. */
    private static final List<String> DEFAULT_ORDER = Arrays.asList(
            "com.google.android.youtube.tv",
            "com.stremio.one",
            "com.netflix.ninja",
            "com.spotify.tv.android");

    private static final String GOOGLE_TV = "com.google.android.apps.tv.launcherx";

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Random random = new Random();

    private Prefs prefs;
    private final ArtMeta meta = new ArtMeta();

    // art layers
    private FrameLayout artRoot;
    private FrameLayout matBox;
    private FrameLayout canvas;
    /** Two cross-fading slots; each holds a blurred backdrop and the sharp image. */
    private FrameLayout[] slots;
    private ImageView[] slotBlur;
    private ImageView[] slotImage;
    private int frontIndex = 0;
    private View nightDim;

    // ui
    private View overlay;
    private TextView timeView;
    private LinearLayout weatherBox;
    private WeatherIcon weatherIcon;
    private TextView weatherText;
    private Weather weather;
    private long weatherFetchedAt = 0;
    private String weatherKey = "";
    private TextView artChip;
    private TextView warning;
    private View searchButton, profileButton, artModeButton, allAppsButton, settingsButton;
    private View[] topButtons;
    private LinearLayout appList;          // dock (horizontal) or list (vertical)
    private ImageView dockBlur;            // frosted-glass backdrop inside the dock
    private View dockPanel;
    private String builtLayout;
    private View lastAppItem;
    private NowPlaying nowPlaying;
    private View dockTint;
    private View listColumn;
    private int lastDockIndex = -1;
    private GradientDrawable infoCardBg;

    // Portrait carousel: 5 cards (partial-left, centre-left, centre-right, partial-right, off-screen).
    private FrameLayout carouselLayer;
    private ImageView carouselBg;
    private GradientField carouselGradient;
    private GradientField dockGradient;
    private GlowRim dockGlow;
    private View dockRim;
    private final List<ImageView> cards = new ArrayList<>();
    private final List<File> cardFiles = new ArrayList<>();
    private boolean carouselOn = false;
    private final Map<String, Boolean> portraitCache = new HashMap<>();
    /** How far the Now playing card slides when the UI hides (toward the screen edge). */
    private int idleCardShift = 0;
    /** How far the dock (and its label) lift to make room for the Now playing card below. */
    private int dockLift = 0;
    private View infoCard;

    private final List<File> artFiles = new ArrayList<>();
    private int lastArt = -1;
    private File currentArt;
    private String dockSignature = "";
    private boolean uiHidden = false;

    private final Runnable nextArt = new Runnable() {
        @Override
        public void run() {
            showNextArt();
            handler.postDelayed(this, prefs.artDurationMs());
        }
    };

    private final Runnable hideUi = new Runnable() {
        @Override
        public void run() {
            if (infoCard.getVisibility() == View.VISIBLE) return;
            uiHidden = true;
            android.view.animation.Interpolator ease = new android.view.animation.AccelerateDecelerateInterpolator();
            overlay.animate().alpha(0f).setDuration(FADE_MS).setInterpolator(ease).start();
            if (dockPanel != null) dockPanel.animate().translationY(dp(36) - dockLift).setDuration(FADE_MS).setInterpolator(ease).start();
            if (listColumn != null) listColumn.animate().translationX(-dp(30)).setDuration(FADE_MS).setInterpolator(ease).start();
            nowPlaying.view.animate().translationY(idleCardShift).setDuration(FADE_MS).start();
            nowPlaying.setIdle(true);
        }
    };

    private final Runnable nightCheck = new Runnable() {
        @Override
        public void run() {
            applyNightMode();
            handler.postDelayed(this, 60_000);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
        prefs = new Prefs(this);
        setContentView(buildLayout());
    }

    @Override
    protected void onResume() {
        super.onResume();
        loadArtList();
        applyFrameStyle();
        applyDisplayPrefs();
        refreshWeather();
        if (!prefs.layout().equals(builtLayout)) {
            recreate();
            return;
        }
        refreshDock();
        refreshAlbumIfStale();
        refreshPacksIfEmpty();
        indexLocalPictures();
        updateWarning();
        handler.removeCallbacks(nextArt);
        handler.post(nextArt);
        handler.removeCallbacks(nightCheck);
        handler.post(nightCheck);
        wakeUi();
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (nowPlaying != null) nowPlaying.start();
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (nowPlaying != null) nowPlaying.stop();
    }

    @Override
    protected void onPause() {
        super.onPause();
        handler.removeCallbacks(nextArt);
        handler.removeCallbacks(hideUi);
        handler.removeCallbacks(nightCheck);
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            boolean wasHidden = uiHidden;
            wakeUi();
            int code = event.getKeyCode();
            if (code == KeyEvent.KEYCODE_SEARCH) {
                openSearch();
                return true;
            }
            if (code == KeyEvent.KEYCODE_INFO || code == KeyEvent.KEYCODE_GUIDE) {
                showInfoCard();
                return true;
            }
            // The first key press after idle only brings the UI back.
            if (wasHidden && code != KeyEvent.KEYCODE_HOME) return true;
        }
        return super.dispatchKeyEvent(event);
    }

    @Override
    public void onBackPressed() {
        if (infoCard.getVisibility() == View.VISIBLE) hideInfoCard();
        // Otherwise we're already home; nothing to go back to.
    }

    private void wakeUi() {
        handler.removeCallbacks(hideUi);
        if (uiHidden) {
            uiHidden = false;
            overlay.animate().alpha(1f).setDuration(300).start();
            if (dockPanel != null) dockPanel.animate().translationY(-dockLift).setDuration(300).start();
            if (listColumn != null) listColumn.animate().translationX(0).setDuration(300).start();
            nowPlaying.view.animate().translationY(0).setDuration(300).start();
            nowPlaying.setIdle(false);
        }
        if (prefs.idleHideMs() > 0) handler.postDelayed(hideUi, prefs.idleHideMs());
    }

    // ---------------------------------------------------------------- layout

    private int dp(float v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                getResources().getDisplayMetrics());
    }

    private static FrameLayout.LayoutParams match() {
        return new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT);
    }

    private void styleText(TextView tv, float sp, int color) {
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        tv.setTextColor(color);
        tv.setShadowLayer(dp(4), 0, dp(1), 0x99000000);
    }

    private View buildLayout() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);

        // Art: outer frame colour -> mat -> canvas holding two cross-fading images.
        artRoot = new FrameLayout(this);
        matBox = new FrameLayout(this);
        canvas = new FrameLayout(this);
        canvas.setClipChildren(true);
        slots = new FrameLayout[2];
        slotBlur = new ImageView[2];
        slotImage = new ImageView[2];
        for (int i = 0; i < 2; i++) {
            FrameLayout slot = new FrameLayout(this);
            slot.setAlpha(0f);
            ImageView blur = new ImageView(this);
            blur.setScaleType(ImageView.ScaleType.CENTER_CROP);
            blur.setRenderEffect(android.graphics.RenderEffect.createBlurEffect(
                    dp(40), dp(40), android.graphics.Shader.TileMode.CLAMP));
            blur.setColorFilter(0x66000000, android.graphics.PorterDuff.Mode.DARKEN);
            ImageView image = new ImageView(this);
            slot.addView(blur, match());
            slot.addView(image, match());
            canvas.addView(slot, match());
            slots[i] = slot;
            slotBlur[i] = blur;
            slotImage[i] = image;
        }
        carouselLayer = new FrameLayout(this);
        carouselLayer.setVisibility(View.GONE);
        carouselBg = new ImageView(this);
        carouselBg.setScaleType(ImageView.ScaleType.CENTER_CROP);
        carouselBg.setRenderEffect(android.graphics.RenderEffect.createBlurEffect(
                dp(50), dp(50), android.graphics.Shader.TileMode.CLAMP));
        carouselBg.setColorFilter(0x88000000, android.graphics.PorterDuff.Mode.DARKEN);
        carouselLayer.setBackgroundColor(0xFF101010);
        carouselLayer.addView(carouselBg, match());
        carouselBg.setVisibility(View.GONE); // replaced by the moving gradient
        carouselGradient = new GradientField(this);
        carouselLayer.addView(carouselGradient, match());
        canvas.addView(carouselLayer, match());
        matBox.addView(canvas, match());
        artRoot.addView(matBox, match());
        root.addView(artRoot, match());

        nightDim = new View(this);
        nightDim.setBackgroundColor(0xFF140A02); // warm, like a dimmed lamp
        nightDim.setAlpha(0f);
        root.addView(nightDim, match());

        FrameLayout ui = new FrameLayout(this);
        overlay = ui;
        root.addView(ui, match());

        builtLayout = prefs.layout();
        buildChrome();
        if (Prefs.LAYOUT_LIST.equals(builtLayout)) buildListLayout(ui); else buildDockLayout(ui);

        // Warning shown when the Home-button service isn't running.
        warning = new TextView(this);
        styleText(warning, 14, Color.WHITE);
        GradientDrawable warnBg = new GradientDrawable();
        warnBg.setColor(0xE0B3261E);
        warnBg.setCornerRadius(dp(10));
        warning.setBackground(warnBg);
        warning.setPadding(dp(16), dp(10), dp(16), dp(10));
        warning.setVisibility(View.GONE);
        FrameLayout.LayoutParams warnLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        warnLp.topMargin = dp(90);
        ui.addView(warning, warnLp);

        Style.font(ui);
        infoCard = buildInfoCard();
        infoCard.setVisibility(View.GONE);
        root.addView(infoCard, match());

        return root;
    }

    private TextView buildArtChip() {
        final TextView chip = new TextView(this);
        styleText(chip, 12, 0xBBFFFFFF);
        chip.setPadding(dp(8), dp(4), dp(10), dp(4));
        final GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(14));
        bg.setColor(Color.TRANSPARENT);
        chip.setBackground(bg);
        chip.setFocusable(true);
        chip.setFocusableInTouchMode(true);
        chip.setOnFocusChangeListener((v, hasFocus) -> {
            bg.setColor(hasFocus ? Color.WHITE : Color.TRANSPARENT);
            chip.setTextColor(hasFocus ? 0xFF111111 : 0xBBFFFFFF);
            chip.setShadowLayer(hasFocus ? 0 : dp(4), 0, dp(1), 0x99000000);
        });
        chip.setOnClickListener(v -> showInfoCard());
        return chip;
    }

    /** Round translucent icon button for the top-right corner; turns white when focused. */
    private View buildActionButton(int glyph, String label, Runnable action) {
        final int size = dp(40);
        FrameLayout button = new FrameLayout(this);
        final GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.OVAL);
        bg.setColor(0x33000000);
        button.setBackground(bg);
        button.setFocusable(true);
        button.setFocusableInTouchMode(true);
        button.setContentDescription(label);

        final GlyphDrawable icon = new GlyphDrawable(glyph, Color.WHITE);
        ImageView iv = new ImageView(this);
        iv.setImageDrawable(icon);
        button.addView(iv, new FrameLayout.LayoutParams(dp(22), dp(22), Gravity.CENTER));

        button.setOnFocusChangeListener((v, hasFocus) -> {
            bg.setColor(hasFocus ? Color.WHITE : 0x33000000);
            icon.setColor(hasFocus ? 0xFF111111 : Color.WHITE);
            float s = hasFocus ? 1.1f : 1f;
            v.animate().scaleX(s).scaleY(s).setDuration(150).start();
        });
        button.setOnClickListener(v -> action.run());

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(size, size);
        lp.setMargins(dp(5), 0, dp(5), 0);
        button.setLayoutParams(lp);
        return button;
    }

    // ---------------------------------------------------------------- display prefs

    private void applyDisplayPrefs() {
        timeView.setVisibility(prefs.showClock() ? View.VISIBLE : View.GONE);
        artChip.setVisibility(prefs.showTitle() && artChip.length() > 0 ? View.VISIBLE : View.GONE);
    }

    private void applyFrameStyle() {
        int w = getResources().getDisplayMetrics().widthPixels;
        String style = prefs.frameStyle();
        int outer = 0, mat = 0;
        int outerColor = Color.BLACK, matColor = Color.BLACK;
        if (Prefs.FRAME_MAT.equals(style)) {
            outerColor = matColor = 0xFFEDEAE4;
            mat = (int) (w * 0.07f);
        } else if (Prefs.FRAME_GALLERY.equals(style)) {
            outerColor = 0xFF3B2A1E;
            matColor = 0xFFF2EFE9;
            outer = (int) (w * 0.03f);
            mat = (int) (w * 0.05f);
        }
        artRoot.setBackgroundColor(outerColor);
        matBox.setBackgroundColor(matColor);
        ((FrameLayout.LayoutParams) matBox.getLayoutParams()).setMargins(outer, outer, outer, outer);
        ((FrameLayout.LayoutParams) canvas.getLayoutParams()).setMargins(mat, mat, mat, mat);
        canvas.setElevation(mat > 0 ? dp(3) : 0);
        canvas.setOutlineProvider(ViewOutlineProvider.BOUNDS);
        matBox.requestLayout();
    }

    private boolean isNight() {
        int h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
        int s = prefs.nightStartHour(), e = prefs.nightEndHour();
        return s <= e ? (h >= s && h < e) : (h >= s || h < e);
    }

    private void applyNightMode() {
        boolean night = prefs.nightMode() && isNight();
        nightDim.animate().alpha(night ? 0.55f : 0f).setDuration(FADE_MS).start();
    }

    private void updateWarning() {
        if (HomeRedirectService.connected) {
            warning.setVisibility(View.GONE);
            return;
        }
        // Give the service a few seconds after boot or an update before warning.
        handler.postDelayed(() -> {
            if (HomeRedirectService.connected) return;
            warning.setText("The Home button can't open Art Home right now: TCL turned off its "
                    + "background permission. Restart the TV to fix it.");
            warning.setVisibility(View.VISIBLE);
        }, 5000);
    }

    // ---------------------------------------------------------------- artwork

    private void loadArtList() {
        artFiles.clear();
        artFiles.addAll(ArtSources.collect(this, prefs));
        meta.clear();
        meta.loadDir(ArtSources.nxtframeDir(this));
        meta.loadDir(ArtPacks.dir(this, Prefs.SOURCE_MET));
        meta.loadDir(ArtPacks.dir(this, Prefs.SOURCE_AIC));
        meta.loadDir(ArtSources.uploadsDir(this));
        meta.loadIndex(LocalIndexer.indexFile(this));
        if (lastArt >= artFiles.size()) lastArt = -1;
    }

    private void refreshAlbumIfStale() {
        if (!ArtSources.albumNeedsRefresh(prefs)) return;
        new Thread(() -> {
            try {
                ArtSources.refreshAlbum(this, prefs);
                handler.post(this::loadArtList);
            } catch (Exception ignored) {
                // Keep showing whatever is cached; retry on the next resume.
            }
        }).start();
    }

    private static volatile boolean packDownloadRunning = false;

    private void refreshPacksIfEmpty() {
        if (packDownloadRunning) return;
        final List<String> todo = new ArrayList<>();
        for (String s : new String[]{Prefs.SOURCE_MET, Prefs.SOURCE_AIC}) {
            if (prefs.sources().contains(s) && ArtPacks.isEmpty(this, s)) todo.add(s);
        }
        if (todo.isEmpty()) return;
        packDownloadRunning = true;
        new Thread(() -> {
            for (String s : todo) {
                try {
                    ArtPacks.download(this, s, null);
                } catch (Exception ignored) {
                }
            }
            packDownloadRunning = false;
            handler.post(this::loadArtList);
        }).start();
    }

    private void refreshWeather() {
        if (!prefs.showWeather()) {
            weatherBox.setVisibility(View.GONE);
            return;
        }
        String key = prefs.weatherCity() + "|" + prefs.fahrenheit();
        if (weather != null) showWeather();
        boolean stale = System.currentTimeMillis() - weatherFetchedAt > Weather.REFRESH_MS;
        if (!stale && key.equals(weatherKey)) return;
        weatherKey = key;
        new Thread(() -> {
            try {
                Weather w = Weather.fetch(prefs);
                handler.post(() -> {
                    weather = w;
                    weatherFetchedAt = System.currentTimeMillis();
                    showWeather();
                });
            } catch (Exception ignored) {
                // Keep the last reading (or nothing); try again on the next resume.
                weatherFetchedAt = 0;
            }
        }).start();
        handler.removeCallbacks(weatherTick);
        handler.postDelayed(weatherTick, Weather.REFRESH_MS);
    }

    private final Runnable weatherTick = this::refreshWeather;

    private void showWeather() {
        weatherIcon.setKind(weather.kind());
        weatherText.setText(weather.temperature(prefs.fahrenheit()));
        weatherBox.setContentDescription(weather.description() + ", " + weather.temperature(prefs.fahrenheit())
                + (weather.place.isEmpty() ? "" : " in " + weather.place));
        weatherBox.setVisibility(View.VISIBLE);
    }

    private void indexLocalPictures() {
        if (!prefs.sources().contains(Prefs.SOURCE_LOCAL)) return;
        new Thread(() -> {
            if (LocalIndexer.run(this)) handler.post(this::loadArtList);
        }).start();
    }

    private void showNextArt() {
        if (artFiles.isEmpty()) return;
        int idx;
        if (prefs.shuffle()) {
            idx = random.nextInt(artFiles.size());
            if (artFiles.size() > 1 && idx == lastArt) idx = (idx + 1) % artFiles.size();
        } else {
            idx = (lastArt + 1) % artFiles.size();
        }
        lastArt = idx;
        final File file = artFiles.get(idx);
        final int w = getResources().getDisplayMetrics().widthPixels;
        final int h = getResources().getDisplayMetrics().heightPixels;
        final boolean pairs = Prefs.PORTRAIT_PAIR.equals(prefs.portraitMode());
        final boolean carousel = Prefs.PORTRAIT_CAROUSEL.equals(prefs.portraitMode());
        final int startIdx = idx;
        new Thread(() -> {
            if (carousel && isPortrait(file)) {
                carouselStep(file, startIdx);
                return;
            }
            Bitmap bmp = decode(file, w, h);
            if (bmp == null) return;
            if (pairs && bmp.getHeight() > bmp.getWidth()) {
                Bitmap partner = findPortraitPartner(file, w, h);
                if (partner != null) bmp = sideBySide(bmp, partner, w, h);
            }
            final Bitmap shown = bmp;
            final int color = Style.muted(shown);
            handler.post(() -> {
                Style.artColor = color;
                hideCarousel();
                crossfadeTo(shown, file);
            });
        }).start();
    }

    private boolean isPortrait(File f) {
        Boolean p = portraitCache.get(f.getAbsolutePath());
        if (p != null) return p;
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(f.getAbsolutePath(), o);
        p = o.outHeight > o.outWidth * 1.15f;
        portraitCache.put(f.getAbsolutePath(), p);
        return p;
    }

    private int cardW() {
        return Math.round(cardH() * 9f / 16f);
    }

    private int cardH() {
        int ch = canvas.getHeight() > 0 ? canvas.getHeight() : getResources().getDisplayMetrics().heightPixels;
        return Math.round(ch * 0.74f);
    }

    private int cardGap() {
        return dp(30);
    }

    /** Left edge of carousel position k (0 = partial-left ... 4 = off-screen right). */
    private float cardX(int k) {
        int cw = canvas.getWidth() > 0 ? canvas.getWidth() : getResources().getDisplayMetrics().widthPixels;
        float center = cw / 2f;
        return center - cardGap() / 2f - cardW() - (cardW() + cardGap()) + k * (cardW() + cardGap());
    }

    /** Runs on a background thread: starts the carousel, or slides it one step. */
    private void carouselStep(File file, int fromIdx) {
        int cw = cardW(), ch = cardH();
        if (!carouselOn || cards.size() < 5) {
            List<File> files = new ArrayList<>();
            files.add(file);
            // Gather more portrait pictures that come after this one.
            int n = artFiles.size(), lastUsed = fromIdx;
            for (int step = 1; step < n && files.size() < 5; step++) {
                int j = prefs.shuffle() ? random.nextInt(n) : (fromIdx + step) % n;
                File f = artFiles.get(j);
                if (!files.contains(f) && isPortrait(f)) {
                    files.add(f);
                    lastUsed = j;
                }
            }
            if (files.size() < 5) {
                // Not enough portraits: show this one normally instead.
                final Bitmap b = decode(file, getResources().getDisplayMetrics().widthPixels,
                        getResources().getDisplayMetrics().heightPixels);
                if (b == null) return;
                final int color = Style.muted(b);
                handler.post(() -> {
                    Style.artColor = color;
                    hideCarousel();
                    crossfadeTo(b, file);
                });
                return;
            }
            final List<Bitmap> bmps = new ArrayList<>();
            for (File f : files) bmps.add(decode(f, cw, ch));
            final int used = lastUsed;
            handler.post(() -> {
                if (!prefs.shuffle()) lastArt = used;
                startCarousel(files, bmps);
            });
        } else {
            // Don't add a photo that's already on screen; pick another portrait instead.
            File next = file;
            if (cardFiles.contains(next)) {
                List<File> pool = new ArrayList<>(artFiles);
                Collections.shuffle(pool, random);
                for (File f : pool) {
                    if (!cardFiles.contains(f) && isPortrait(f)) {
                        next = f;
                        break;
                    }
                }
            }
            final File chosen = next;
            final Bitmap b = decode(chosen, cw, ch);
            handler.post(() -> advanceCarousel(chosen, b));
        }
    }

    private ImageView makeCard(Bitmap b) {
        ImageView iv = new ImageView(this);
        iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
        iv.setImageBitmap(b);
        final int radius = dp(18);
        iv.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), radius);
            }
        });
        iv.setClipToOutline(true);
        iv.setLayerType(View.LAYER_TYPE_HARDWARE, null); // cached: the gradient redraws behind it
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(cardW(), cardH(), Gravity.TOP | Gravity.START);
        int ch = canvas.getHeight() > 0 ? canvas.getHeight() : getResources().getDisplayMetrics().heightPixels;
        lp.topMargin = (ch - cardH()) / 2;
        carouselLayer.addView(iv, lp);
        return iv;
    }

    private void startCarousel(List<File> files, List<Bitmap> bmps) {
        for (ImageView iv : cards) carouselLayer.removeView(iv);
        cards.clear();
        cardFiles.clear();
        for (int k = 0; k < files.size(); k++) {
            ImageView iv = makeCard(bmps.get(k));
            iv.setTranslationX(cardX(k));
            cards.add(iv);
            cardFiles.add(files.get(k));
        }
        carouselOn = true;
        RimDrawable.gradientOn = true;
        if (dockGradient != null) {
            dockGradient.setVisibility(View.VISIBLE);
            dockBlur.setVisibility(View.GONE);
            dockGlow.setVisibility(View.VISIBLE);
            dockRim.setVisibility(View.INVISIBLE);
        }
        carouselLayer.bringToFront();
        carouselLayer.setAlpha(0f);
        carouselLayer.setVisibility(View.VISIBLE);
        carouselLayer.animate().alpha(1f).setDuration(FADE_MS).start();
        onCarouselCentre(bmps.get(2), files.get(2));
    }

    /** Glide everything one place left: the right-hand photo moves into the centre. */
    private void advanceCarousel(File file, Bitmap b) {
        // Two quick steps (timer + returning home) can pick the same photo; skip the repeat.
        if (b == null || cards.size() < 5 || cardFiles.contains(file)) return;
        ImageView incoming = makeCard(b);
        incoming.setTranslationX(cardX(5));
        cards.add(incoming);
        cardFiles.add(file);
        final ImageView leaving = cards.remove(0);
        cardFiles.remove(0);
        // Glide to fixed slots (not relative moves), so quick successive changes can't drift.
        android.view.animation.Interpolator ease = new android.view.animation.AccelerateDecelerateInterpolator();
        leaving.animate().cancel();
        leaving.animate().translationX(cardX(-1)).setInterpolator(ease).setDuration(1400).start();
        for (int k = 0; k < cards.size(); k++) {
            ImageView iv = cards.get(k);
            iv.animate().cancel();
            iv.animate().translationX(cardX(k)).setInterpolator(ease).setDuration(1400).start();
        }
        handler.postDelayed(() -> carouselLayer.removeView(leaving), 1500);
        Bitmap centre = ((android.graphics.drawable.BitmapDrawable) cards.get(2).getDrawable()).getBitmap();
        onCarouselCentre(centre, cardFiles.get(2));
    }

    /** Updates the backdrop, colours and artwork label for the photo now in the centre. */
    private void onCarouselCentre(Bitmap b, File f) {
        carouselBg.setImageBitmap(b);
        // Gradient colours from the four photos currently in view.
        int[] colors = new int[4];
        for (int k = 0; k < 4 && k < cards.size(); k++) {
            android.graphics.drawable.Drawable d = cards.get(k).getDrawable();
            colors[k] = d instanceof android.graphics.drawable.BitmapDrawable
                    ? Style.muted(((android.graphics.drawable.BitmapDrawable) d).getBitmap()) : 0xFF2A2A2A;
        }
        carouselGradient.setColors(colors);
        Style.artColor = Style.muted(b);
        currentArt = f;
        Style.currentArt = f;
        ArtMeta.Info info = meta.get(f);
        artChip.setText(info == null ? "ⓘ" : "ⓘ  " + info.title + (info.artist.isEmpty() ? "" : " — " + info.artist));
        applyDisplayPrefs();
        wireFocus();
        tintGlass(Style.artColor);
        if (dockBlur != null) dockBlur.setImageBitmap(b);
        if (infoCard.getVisibility() == View.VISIBLE) fillInfoCard();
    }

    private void hideCarousel() {
        if (!carouselOn) return;
        carouselOn = false;
        RimDrawable.gradientOn = false;
        tintGlass(Style.artColor);
        if (dockGradient != null) {
            dockGradient.setVisibility(View.GONE);
            dockBlur.setVisibility(View.VISIBLE);
            dockGlow.setVisibility(View.GONE);
            dockRim.setVisibility(View.VISIBLE);
        }
        carouselLayer.animate().alpha(0f).setDuration(FADE_MS).withEndAction(() -> {
            carouselLayer.setVisibility(View.GONE);
            for (ImageView iv : cards) carouselLayer.removeView(iv);
            cards.clear();
            cardFiles.clear();
        }).start();
    }

    /** Another portrait picture (tries a few at random), decoded for half the screen. */
    private Bitmap findPortraitPartner(File first, int w, int h) {
        List<File> files = new ArrayList<>(artFiles);
        Collections.shuffle(files, random);
        int tries = 0;
        for (File f : files) {
            if (f.equals(first) || tries++ > 12) continue;
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(f.getAbsolutePath(), o);
            if (o.outHeight > o.outWidth * 1.15f) return decode(f, w / 2, h);
        }
        return null;
    }

    /** Two portrait photos, each fitted into half the screen, on a blurred-dark backdrop. */
    private Bitmap sideBySide(Bitmap a, Bitmap b, int w, int h) {
        Bitmap out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        android.graphics.Canvas c = new android.graphics.Canvas(out);
        c.drawColor(Style.blend(Style.muted(a), 0xFF000000, 0.5f));
        android.graphics.Paint p = new android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG);
        int gap = dp(14), margin = dp(28);
        Bitmap[] pics = {a, b};
        // Fit both to the same height, then centre them together as a pair.
        float boxW = (w - margin * 2 - gap) / 2f, boxH = h - margin * 2;
        float[] dws = new float[2], dhs = new float[2];
        for (int i = 0; i < 2; i++) {
            float scale = Math.min(boxW / pics[i].getWidth(), boxH / pics[i].getHeight());
            dws[i] = pics[i].getWidth() * scale;
            dhs[i] = pics[i].getHeight() * scale;
        }
        float x = (w - dws[0] - gap - dws[1]) / 2f;
        for (int i = 0; i < 2; i++) {
            float dw = dws[i], dh = dhs[i];
            float left = x, top = margin + (boxH - dh) / 2;
            x += dw + gap;
            android.graphics.RectF dst = new android.graphics.RectF(left, top, left + dw, top + dh);
            c.save();
            android.graphics.Path clip = new android.graphics.Path();
            clip.addRoundRect(dst, dp(14), dp(14), android.graphics.Path.Direction.CW);
            c.clipPath(clip);
            c.drawBitmap(pics[i], null, dst, p);
            c.restore();
        }
        return out;
    }

    private static Bitmap decode(File f, int reqW, int reqH) {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(f.getAbsolutePath(), o);
        int sample = 1;
        while (o.outWidth / (sample * 2) >= reqW && o.outHeight / (sample * 2) >= reqH) sample *= 2;
        o = new BitmapFactory.Options();
        o.inSampleSize = sample;
        return BitmapFactory.decodeFile(f.getAbsolutePath(), o);
    }

    private void crossfadeTo(Bitmap bmp, File file) {
        FrameLayout front = slots[frontIndex];
        FrameLayout back = slots[1 - frontIndex];
        final ImageView image = slotImage[1 - frontIndex];
        ImageView blur = slotBlur[1 - frontIndex];

        // Landscape art fills the screen. Portrait or square photos are shown whole over a
        // blurred copy, instead of cropping a thin band and blowing it up.
        float canvasAspect = canvas.getWidth() > 0 ? (float) canvas.getWidth() / canvas.getHeight() : 16f / 9f;
        float imageAspect = (float) bmp.getWidth() / bmp.getHeight();
        final boolean fit = imageAspect < canvasAspect * 0.8f;
        image.animate().cancel();
        image.setScaleX(1f);
        image.setScaleY(1f);
        image.setTranslationX(0);
        image.setTranslationY(0);
        image.setScaleType(fit ? ImageView.ScaleType.FIT_CENTER : ImageView.ScaleType.CENTER_CROP);
        image.setImageBitmap(bmp);
        blur.setImageBitmap(fit ? bmp : null);
        blur.setVisibility(fit ? View.VISIBLE : View.GONE);

        back.animate().cancel();
        back.bringToFront();
        carouselLayer.bringToFront();
        back.animate().alpha(1f).setDuration(FADE_MS).withEndAction(() -> {
            if (prefs.kenBurns()) kenBurns(image, fit);
        }).start();
        front.animate().alpha(0f).setDuration(FADE_MS).start();
        frontIndex = 1 - frontIndex;
        currentArt = file;

        // Every picture gets a chip, so its card (favourite / never show / next) is always reachable.
        // Pictures without details show only a small ⓘ (still selectable for the card).
        ArtMeta.Info info = meta.get(file);
        artChip.setText(info == null ? "ⓘ" : "ⓘ  " + info.title + (info.artist.isEmpty() ? "" : " — " + info.artist));
        Style.currentArt = file;
        tintGlass(Style.artColor);
        applyDisplayPrefs();
        wireFocus();
        if (dockBlur != null) dockBlur.setImageBitmap(bmp);
        if (infoCard.getVisibility() == View.VISIBLE) fillInfoCard();
    }

    /** Fades the frosted dock and info card toward the new artwork's colour. */
    private void tintGlass(int color) {
        if (dockTint != null) {
            int from = ((android.graphics.drawable.ColorDrawable) dockTint.getBackground()).getColor();
            // Lighter over the moving gradient so more of it shows through the glass.
            int alpha = carouselOn ? 0x14 : 0x59;
            android.animation.ValueAnimator va = android.animation.ValueAnimator.ofArgb(from, Style.withAlpha(color, alpha));
            va.setDuration(FADE_MS);
            va.addUpdateListener(an -> dockTint.setBackgroundColor((int) an.getAnimatedValue()));
            va.start();
        }
        if (infoCardBg != null) infoCardBg.setColor(Style.withAlpha(Style.blend(color, 0xFF000000, 0.45f), 0xE6));
    }

    /** Slow pan and zoom across the artwork for the time it is on screen. */
    private void kenBurns(ImageView iv, boolean fitted) {
        // Gentler on fitted photos so they stay sharp and fully visible.
        float zoom = fitted ? 1.03f : 1.08f;
        float maxShiftX = iv.getWidth() * (zoom - 1f) / 2f;
        float maxShiftY = iv.getHeight() * (zoom - 1f) / 2f;
        iv.animate()
                .scaleX(zoom).scaleY(zoom)
                .translationX((random.nextFloat() * 2 - 1) * maxShiftX)
                .translationY((random.nextFloat() * 2 - 1) * maxShiftY)
                .setDuration(prefs.artDurationMs())
                .setInterpolator(new android.view.animation.LinearInterpolator())
                .start();
    }

    // ---------------------------------------------------------------- info card

    private TextView cardTitle, cardArtist, cardDesc;
    private TextView favButton;

    private View buildInfoCard() {
        FrameLayout scrim = new FrameLayout(this);
        scrim.setBackgroundColor(0x66000000);
        scrim.setClickable(true);

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(32), dp(28), dp(32), dp(24));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Style.withAlpha(Style.blend(Style.artColor, 0xFF000000, 0.45f), 0xE6));
        bg.setCornerRadius(dp(28));
        bg.setStroke(dp(1), 0x26FFFFFF);
        infoCardBg = bg;
        card.setBackground(bg);

        cardTitle = new TextView(this);
        cardTitle.setTextColor(Color.WHITE);
        cardTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 26);
        cardArtist = new TextView(this);
        cardArtist.setTextColor(0xFFCFD3D8);
        cardArtist.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        cardArtist.setPadding(0, dp(4), 0, dp(12));
        cardDesc = new TextView(this);
        cardDesc.setTextColor(0xFFB0B4BA);
        cardDesc.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        cardDesc.setLineSpacing(0, 1.2f);
        card.addView(cardTitle);
        card.addView(cardArtist);
        card.addView(cardDesc);

        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        buttons.setPadding(0, dp(20), 0, 0);
        favButton = cardButton("♥  Favourite", () -> {
            if (currentArt == null) return;
            boolean fav = !prefs.favourites().contains(currentArt.getAbsolutePath());
            prefs.setFavourite(currentArt.getAbsolutePath(), fav);
            fillInfoCard();
        });
        buttons.addView(favButton);
        buttons.addView(cardButton("Never show this", () -> {
            if (currentArt == null) return;
            prefs.block(currentArt.getAbsolutePath());
            loadArtList();
            showNextArt();
            hideInfoCard();
        }));
        buttons.addView(cardButton("Next artwork", () -> {
            handler.removeCallbacks(nextArt);
            handler.post(nextArt);
        }));
        buttons.addView(cardButton("Close", this::hideInfoCard));
        card.addView(buttons);

        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(dp(640),
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER);
        scrim.addView(card, lp);
        Style.font(scrim);
        Style.setFont(cardTitle, Style.medium(this));
        return scrim;
    }

    private TextView cardButton(String text, Runnable onClick) {
        final TextView b = new TextView(this);
        b.setText(text);
        b.setTextColor(Color.WHITE);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        b.setPadding(dp(16), dp(10), dp(16), dp(10));
        final GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(20));
        bg.setColor(0x33FFFFFF);
        b.setBackground(bg);
        b.setFocusable(true);
        b.setFocusableInTouchMode(true);
        b.setOnFocusChangeListener((v, hasFocus) -> {
            bg.setColor(hasFocus ? Color.WHITE : 0x33FFFFFF);
            b.setTextColor(hasFocus ? 0xFF111111 : Color.WHITE);
        });
        b.setOnClickListener(v -> onClick.run());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.rightMargin = dp(10);
        b.setLayoutParams(lp);
        return b;
    }

    private void fillInfoCard() {
        ArtMeta.Info info = currentArt == null ? null : meta.get(currentArt);
        if (info != null) {
            cardTitle.setText(info.title);
            cardArtist.setText(info.artist);
            cardDesc.setText(info.description);
        } else {
            cardTitle.setText(currentArt == null ? "No artwork" : "Untitled");
            cardArtist.setText(currentArt == null ? "" : sourceLabel(currentArt));
            cardDesc.setText("");
        }
        cardArtist.setVisibility(cardArtist.length() > 0 ? View.VISIBLE : View.GONE);
        cardDesc.setVisibility(cardDesc.length() > 0 ? View.VISIBLE : View.GONE);
        boolean fav = currentArt != null && prefs.favourites().contains(currentArt.getAbsolutePath());
        favButton.setText(fav ? "♥  Favourited" : "♡  Favourite");
    }

    private String sourceLabel(File f) {
        String p = f.getAbsolutePath();
        if (p.contains("/gphotos/")) return "Google Photos";
        if (p.contains("/pack_met/")) return "The Metropolitan Museum of Art";
        if (p.contains("/pack_aic/")) return "Art Institute of Chicago";
        if (p.contains("/uploads/")) return "Sent from your phone";
        if (p.contains("/files/art/")) return "TCL NXTFRAME";
        return "My pictures (USB)";
    }

    private void showInfoCard() {
        fillInfoCard();
        // Keep remote focus inside the card while it is open.
        ((ViewGroup) overlay).setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        infoCard.setVisibility(View.VISIBLE);
        infoCard.setAlpha(0f);
        infoCard.animate().alpha(1f).setDuration(200).start();
        favButton.requestFocus();
    }

    private void hideInfoCard() {
        ((ViewGroup) overlay).setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);
        infoCard.setVisibility(View.GONE);
        artChip.requestFocus();
    }

    // ---------------------------------------------------------------- layouts

    /** Clock, weather, artwork label and the top buttons shared by both layouts. */
    private void buildChrome() {
        TextClock time = new TextClock(this);
        time.setFormat24Hour("H:mm");
        time.setFormat12Hour("h:mm");
        styleText(time, 32, 0xF2FFFFFF);
        Style.setFont(time, Style.light(this));
        timeView = time;

        weatherBox = new LinearLayout(this);
        weatherBox.setOrientation(LinearLayout.HORIZONTAL);
        weatherBox.setGravity(Gravity.CENTER_VERTICAL);
        weatherIcon = new WeatherIcon(WeatherIcon.SUN, 0xF2FFFFFF);
        ImageView wi = new ImageView(this);
        wi.setImageDrawable(weatherIcon);
        weatherBox.addView(wi, new LinearLayout.LayoutParams(dp(24), dp(24)));
        weatherText = new TextView(this);
        styleText(weatherText, 20, 0xE6FFFFFF);
        weatherText.setPadding(dp(6), 0, 0, 0);
        weatherBox.addView(weatherText);
        weatherBox.setVisibility(View.GONE);

        boolean labelled = Prefs.LAYOUT_DOCK.equals(builtLayout);
        searchButton = topButton(GlyphDrawable.SEARCH, "Search", false, this::openSearch);
        profileButton = topButton(GlyphDrawable.PROFILE, "Profile", false, this::openProfiles);
        artModeButton = topButton(GlyphDrawable.FRAME, "Art Mode", labelled, this::openArtMode);
        allAppsButton = topButton(GlyphDrawable.GRID, "All apps", labelled,
                () -> openScreen(AllAppsActivity.class));
        settingsButton = topButton(GlyphDrawable.SETTINGS, "Settings", labelled,
                () -> openScreen(SettingsActivity.class));
        topButtons = new View[]{searchButton, profileButton, artModeButton, allAppsButton, settingsButton};
        artChip = buildArtChip();
        nowPlaying = new NowPlaying(this);
        nowPlaying.setRim(carouselGradient);
        nowPlaying.setOnVisibilityChanged(this::wireFocus);
        for (View v : topButtons) v.setId(View.generateViewId());
        artChip.setId(View.generateViewId());
    }

    /** Icon button; with a text label in the dock layout, icon-only in the list layout. */
    private View topButton(int glyph, String label, boolean withLabel, Runnable action) {
        LinearLayout b = new LinearLayout(this);
        b.setOrientation(LinearLayout.HORIZONTAL);
        b.setGravity(Gravity.CENTER);
        b.setPadding(dp(withLabel ? 12 : 9), dp(8), dp(withLabel ? 14 : 9), dp(8));
        final GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(20));
        bg.setColor(Color.TRANSPARENT);
        b.setBackground(bg);
        b.setFocusable(true);
        b.setFocusableInTouchMode(true);
        b.setContentDescription(label);
        final GlyphDrawable icon = new GlyphDrawable(glyph, 0xF2FFFFFF);
        ImageView iv = new ImageView(this);
        iv.setImageDrawable(icon);
        b.addView(iv, new LinearLayout.LayoutParams(dp(20), dp(20)));
        final TextView t = new TextView(this);
        if (withLabel) {
            t.setText(label);
            styleText(t, 16, 0xF2FFFFFF);
            t.setPadding(dp(8), 0, 0, 0);
            b.addView(t);
        }
        b.setOnFocusChangeListener((v, f) -> {
            bg.setColor(f ? 0xF2FFFFFF : Color.TRANSPARENT);
            icon.setColor(f ? 0xFF111111 : 0xF2FFFFFF);
            t.setTextColor(f ? 0xFF111111 : 0xF2FFFFFF);
            t.setShadowLayer(f ? 0 : dp(4), 0, dp(1), 0x99000000);
        });
        b.setOnClickListener(v -> action.run());
        return b;
    }

    private void addShade(FrameLayout ui, GradientDrawable.Orientation o, int color, int w, int h, int gravity) {
        View v = new View(this);
        v.setBackground(new GradientDrawable(o, new int[]{color, 0x00000000}));
        ui.addView(v, new FrameLayout.LayoutParams(w, h, gravity));
    }

    /** Design 1: time top-left, labelled buttons top-right, frosted-glass dock at the bottom. */
    private void buildDockLayout(FrameLayout ui) {
        addShade(ui, GradientDrawable.Orientation.TOP_BOTTOM, 0x55000000,
                ViewGroup.LayoutParams.MATCH_PARENT, dp(140), Gravity.TOP);

        LinearLayout left = new LinearLayout(this);
        left.setGravity(Gravity.CENTER_VERTICAL);
        left.addView(timeView);
        LinearLayout.LayoutParams wLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        wLp.leftMargin = dp(22);
        left.addView(weatherBox, wLp);
        FrameLayout.LayoutParams leftLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.START);
        leftLp.setMargins(dp(64), dp(32), 0, 0);
        ui.addView(left, leftLp);

        LinearLayout right = new LinearLayout(this);
        right.setGravity(Gravity.CENTER_VERTICAL);
        for (View b : topButtons) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.leftMargin = dp(6);
            right.addView(b, lp);
        }
        FrameLayout.LayoutParams rightLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.END);
        rightLp.setMargins(0, dp(30), dp(56), 0);
        ui.addView(right, rightLp);
        for (int i = 0; i < topButtons.length; i++) {
            topButtons[i].setNextFocusLeftId(topButtons[Math.max(0, i - 1)].getId());
            topButtons[i].setNextFocusRightId(topButtons[Math.min(topButtons.length - 1, i + 1)].getId());
        }

        // Frosted-glass dock: a blurred copy of the artwork, clipped to a rounded panel.
        FrameLayout panel = new FrameLayout(this);
        dockPanel = panel;
        final float radius = dp(28);
        panel.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), radius);
            }
        });
        panel.setClipToOutline(true);
        dockBlur = new ImageView(this);
        dockBlur.setScaleType(ImageView.ScaleType.CENTER_CROP);
        dockBlur.setRenderEffect(android.graphics.RenderEffect.createBlurEffect(
                dp(36), dp(36), android.graphics.Shader.TileMode.CLAMP));
        panel.addView(dockBlur, new FrameLayout.LayoutParams(
                getResources().getDisplayMetrics().widthPixels, getResources().getDisplayMetrics().heightPixels));
        // Copy of the carousel's moving gradient, so it flows through the glass too.
        dockGradient = new GradientField(this, carouselGradient);
        dockGradient.setVisibility(View.GONE);
        panel.addView(dockGradient, new FrameLayout.LayoutParams(
                getResources().getDisplayMetrics().widthPixels, getResources().getDisplayMetrics().heightPixels));
        View tint = new View(this);
        tint.setBackgroundColor(Style.withAlpha(Style.artColor, 0x59));
        dockTint = tint;
        panel.addView(tint, match());
        GradientDrawable rim = new GradientDrawable();
        rim.setCornerRadius(radius);
        rim.setColor(Color.TRANSPARENT);
        rim.setStroke(Math.max(2, dp(1.5f)), 0x59FFFFFF); // bright enough to frame the light glass
        View rimView = new View(this);
        rimView.setBackground(rim);
        dockRim = rimView;
        panel.addView(rimView, match());

        // Wrapper (not clipped) so the glow can spread just outside the dock's edge.
        final int g = dp(7);
        final FrameLayout wrap = new FrameLayout(this);
        wrap.setClipChildren(false);
        wrap.setClipToPadding(false);
        dockGlow = new GlowRim(this, carouselGradient, radius, g, dp(1.5f));
        dockGlow.setVisibility(View.GONE);
        FrameLayout.LayoutParams inner = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        inner.setMargins(g, g, g, g);
        wrap.addView(panel, inner);
        wrap.addView(dockGlow, match()); // on top, so the whole rim is visible
        panel.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            int x = wrap.getLeft() + l, y = wrap.getTop() + t;
            dockBlur.setTranslationX(-x);
            dockBlur.setTranslationY(-y);
            if (dockGradient != null) {
                dockGradient.setTranslationX(-x);
                dockGradient.setTranslationY(-y - wrap.getTranslationY());
            }
        });

        HorizontalScrollView hs = new HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        hs.setFillViewport(true);
        appList = new LinearLayout(this);
        appList.setOrientation(LinearLayout.HORIZONTAL);
        appList.setPadding(dp(10), dp(10), dp(10), dp(10));
        hs.addView(appList);
        panel.addView(hs, match());

        FrameLayout.LayoutParams panelLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(122) + 2 * g, Gravity.BOTTOM);
        // Sits low; lifts only when a Now playing card needs the space below it.
        panelLp.setMargins(dp(48) - g, 0, dp(48) - g, dp(36) - g);
        ui.addView(wrap, panelLp);
        dockPanel = wrap; // moves (lift / idle slide) as one, glow included

        FrameLayout.LayoutParams chipLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM | Gravity.START);
        chipLp.setMargins(dp(58), 0, 0, dp(36 + 122 + 10));
        ui.addView(artChip, chipLp);

        FrameLayout.LayoutParams npLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM | Gravity.END);
        npLp.setMargins(0, 0, dp(48), dp(24));
        // Not inside the overlay, so it stays when the rest fades out for the slideshow.
        ((ViewGroup) ui.getParent()).addView(nowPlaying.view, npLp);
        idleCardShift = 0;
    }

    /** Design 2: big text list on the left, time top-right, icon buttons stacked bottom-right. */
    private void buildListLayout(FrameLayout ui) {
        addShade(ui, GradientDrawable.Orientation.LEFT_RIGHT, 0x66000000,
                dp(560), ViewGroup.LayoutParams.MATCH_PARENT, Gravity.START);
        addShade(ui, GradientDrawable.Orientation.TOP_BOTTOM, 0x44000000,
                ViewGroup.LayoutParams.MATCH_PARENT, dp(130), Gravity.TOP);

        LinearLayout topRight = new LinearLayout(this);
        topRight.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams wLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        wLp.rightMargin = dp(22);
        topRight.addView(weatherBox, wLp);
        topRight.addView(timeView);
        FrameLayout.LayoutParams trLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.END);
        trLp.setMargins(0, dp(32), dp(64), 0);
        ui.addView(topRight, trLp);

        FrameLayout.LayoutParams npLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.END);
        npLp.setMargins(0, dp(92), dp(56), 0);
        ((ViewGroup) ui.getParent()).addView(nowPlaying.view, npLp);
        idleCardShift = -dp(92 - 36);

        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setGravity(Gravity.CENTER_HORIZONTAL);
        for (View b : topButtons) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.topMargin = dp(6);
            column.addView(b, lp);
        }
        FrameLayout.LayoutParams colLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM | Gravity.END);
        colLp.setMargins(0, 0, dp(58), dp(48));
        ui.addView(column, colLp);
        for (int i = 0; i < topButtons.length; i++) {
            topButtons[i].setNextFocusUpId(topButtons[Math.max(0, i - 1)].getId());
            topButtons[i].setNextFocusDownId(topButtons[Math.min(topButtons.length - 1, i + 1)].getId());
            topButtons[i].setNextFocusRightId(topButtons[i].getId());
        }

        ScrollView sv = new ScrollView(this);
        sv.setVerticalScrollBarEnabled(false);
        sv.setVerticalFadingEdgeEnabled(true);
        sv.setFadingEdgeLength(dp(70));
        sv.setClipToPadding(false);
        sv.setClipChildren(false);
        appList = new LinearLayout(this);
        appList.setOrientation(LinearLayout.VERTICAL);
        appList.setClipChildren(false);
        appList.setClipToPadding(false);
        appList.setPadding(0, dp(10), 0, dp(10));
        sv.addView(appList);
        listColumn = sv;
        FrameLayout.LayoutParams listLp = new FrameLayout.LayoutParams(
                dp(560), ViewGroup.LayoutParams.MATCH_PARENT, Gravity.START);
        listLp.setMargins(dp(50), dp(92), 0, dp(96));
        ui.addView(sv, listLp);

        FrameLayout.LayoutParams chipLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM | Gravity.START);
        chipLp.setMargins(dp(58), 0, 0, dp(48));
        ui.addView(artChip, chipLp);
    }

    private String dockSig = "";

    private void refreshDock() {
        List<Apps.Entry> apps = Apps.dock(this, prefs);
        StringBuilder sig = new StringBuilder();
        for (Apps.Entry a : apps) sig.append(a.key).append(';');
        if (sig.toString().equals(dockSig) && appList.getChildCount() > 0) {
            wireFocus();
            return;
        }
        dockSig = sig.toString();
        Object focusKey = lastAppItem == null ? null : lastAppItem.getTag();
        appList.removeAllViews();
        boolean dock = Prefs.LAYOUT_DOCK.equals(builtLayout);
        for (Apps.Entry a : apps) appList.addView(dock ? dockItem(a) : listItem(a));
        Style.font(appList);
        View toFocus = focusKey == null ? null : appList.findViewWithTag(focusKey);
        if (toFocus == null && appList.getChildCount() > 0) toFocus = appList.getChildAt(0);
        wireFocus();
        if (toFocus != null) toFocus.requestFocus();
    }

    private View dockItem(final Apps.Entry a) {
        FrameLayout item = new FrameLayout(this);
        item.setTag(a.key);
        item.setId(View.generateViewId());
        item.setFocusable(true);
        item.setFocusableInTouchMode(true);
        item.setContentDescription(a.label);
        item.setCameraDistance(dp(900));
        final GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(20));
        bg.setColor(Color.TRANSPARENT);
        item.setBackground(bg);
        final int radius = dp(20);
        item.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), radius);
            }
        });
        item.setClipToOutline(true);

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setGravity(Gravity.CENTER);
        final ImageView logo = new ImageView(this);
        logo.setImageDrawable(Logos.mono(a));
        content.addView(logo, new LinearLayout.LayoutParams(dp(34), dp(34)));
        final TextView label = new TextView(this);
        label.setText(a.label);
        label.setTextColor(0xF2FFFFFF);
        label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        label.setSingleLine(true);
        label.setEllipsize(android.text.TextUtils.TruncateAt.END);
        label.setGravity(Gravity.CENTER);
        label.setPadding(dp(8), dp(10), dp(8), 0);
        content.addView(label);
        item.addView(content, match());
        final View sheen = Tiles.sheen(this, item);

        item.setOnFocusChangeListener((v, f) -> {
            bg.setColor(f ? 0x40FFFFFF : Color.TRANSPARENT);
            android.view.animation.Interpolator ease = new android.view.animation.DecelerateInterpolator(1.6f);
            logo.animate().scaleX(f ? 1.22f : 1f).scaleY(f ? 1.22f : 1f).translationY(f ? -dp(3) : 0)
                    .setInterpolator(ease).setDuration(220).start();
            label.animate().scaleX(f ? 1.12f : 1f).scaleY(f ? 1.12f : 1f).translationY(f ? dp(2) : 0)
                    .setInterpolator(ease).setDuration(220).start();
            if (f) {
                // Swing in slightly from the direction focus came from.
                int idx = appList.indexOfChild(v);
                if (lastDockIndex >= 0 && idx != lastDockIndex) {
                    v.setRotationY(idx > lastDockIndex ? -10f : 10f);
                    v.animate().rotationY(0).setInterpolator(ease).setDuration(320).start();
                }
                lastDockIndex = idx;
                Tiles.playSheen(sheen, v.getWidth());
                onAppFocused(v);
            }
        });
        item.setOnClickListener(v -> Apps.launch(this, a));
        item.setOnLongClickListener(v -> {
            showDockMenu(a);
            return true;
        });
        int count = Math.max(1, Math.min(6, Apps.dockKeys(this, prefs, Apps.all(this, prefs)).size()));
        float panelDp = getResources().getDisplayMetrics().widthPixels / getResources().getDisplayMetrics().density - 96 - 20;
        item.setLayoutParams(new LinearLayout.LayoutParams(dp(panelDp / count), ViewGroup.LayoutParams.MATCH_PARENT));
        return item;
    }

    private View listItem(final Apps.Entry a) {
        LinearLayout item = new LinearLayout(this);
        item.setOrientation(LinearLayout.HORIZONTAL);
        item.setGravity(Gravity.CENTER_VERTICAL);
        item.setTag(a.key);
        item.setId(View.generateViewId());
        item.setFocusable(true);
        item.setFocusableInTouchMode(true);
        item.setContentDescription(a.label);
        item.setPadding(dp(16), dp(6), dp(26), dp(6));
        final GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(18));
        bg.setColor(Color.TRANSPARENT);
        item.setBackground(bg);
        ImageView logo = new ImageView(this);
        logo.setImageDrawable(Logos.mono(a));
        LinearLayout.LayoutParams logoLp = new LinearLayout.LayoutParams(dp(30), dp(30));
        logoLp.rightMargin = dp(16);
        item.addView(logo, logoLp);
        final TextView label = new TextView(this);
        label.setText(a.label);
        label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 36);
        Style.setFont(label, Style.medium(this));
        label.setSingleLine(true);
        styleText(label, 36, 0xD9FFFFFF);
        item.addView(label);
        // Grow from the left edge when focused, shrink back when focus moves on.
        item.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            v.setPivotX(0);
            v.setPivotY((b - t) / 2f);
        });
        final RimDrawable rim = new RimDrawable(item, carouselGradient, dp(18), dp(1.5f));
        item.setOnFocusChangeListener((v, f) -> {
            bg.setColor(f ? 0x40FFFFFF : Color.TRANSPARENT);
            v.setForeground(f ? rim : null);
            label.setTextColor(f ? Color.WHITE : 0xD9FFFFFF);
            float sc = f ? 1.16f : 1f;
            v.animate().scaleX(sc).scaleY(sc)
                    .setInterpolator(new android.view.animation.DecelerateInterpolator(1.6f))
                    .setDuration(220).start();
            if (f) onAppFocused(v);
        });
        item.setOnClickListener(v -> Apps.launch(this, a));
        item.setOnLongClickListener(v -> {
            showDockMenu(a);
            return true;
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(6); // room for the focused item to grow
        item.setLayoutParams(lp);
        return item;
    }

    private void onAppFocused(View item) {
        lastAppItem = item;
        if (Prefs.LAYOUT_DOCK.equals(builtLayout)) {
            for (View b : topButtons) b.setNextFocusDownId(item.getId());
            artChip.setNextFocusDownId(item.getId());
        } else {
            for (View b : topButtons) b.setNextFocusLeftId(item.getId());
        }
    }

    /** Focus routes that depend on what is currently shown. */
    private void wireFocus() {
        if (appList == null || appList.getChildCount() == 0) return;
        boolean dock = Prefs.LAYOUT_DOCK.equals(builtLayout);
        boolean chip = artChip.getVisibility() == View.VISIBLE;
        int n = appList.getChildCount();
        for (int i = 0; i < n; i++) {
            View it = appList.getChildAt(i);
            if (dock) {
                it.setNextFocusUpId(chip ? artChip.getId() : allAppsButton.getId());
                it.setNextFocusDownId(it.getId());
                if (i == 0) it.setNextFocusLeftId(it.getId());
                if (i == n - 1) it.setNextFocusRightId(it.getId());
            } else {
                it.setNextFocusRightId(allAppsButton.getId());
                it.setNextFocusLeftId(it.getId());
                if (i == 0) it.setNextFocusUpId(it.getId());
                if (i == n - 1) it.setNextFocusDownId(chip ? artChip.getId() : it.getId());
            }
        }
        if (dock) {
            artChip.setNextFocusUpId(allAppsButton.getId());
            artChip.setNextFocusDownId(appList.getChildAt(0).getId());
            for (View b : topButtons) {
                b.setNextFocusDownId((lastAppItem != null ? lastAppItem : appList.getChildAt(0)).getId());
            }
        } else {
            artChip.setNextFocusUpId(appList.getChildAt(n - 1).getId());
            artChip.setNextFocusRightId(searchButton.getId());
            for (View b : topButtons) b.setNextFocusLeftId((lastAppItem != null ? lastAppItem : appList.getChildAt(0)).getId());
        }

        // Now playing card.
        boolean np = nowPlaying.view.getVisibility() == View.VISIBLE;
        View appTarget = lastAppItem != null ? lastAppItem : appList.getChildAt(0);
        View[] npButtons = {nowPlaying.prevButton, nowPlaying.playButton, nowPlaying.nextButton, nowPlaying.closeButton};
        if (dock) {
            // The card sits below the dock: Down from an app reaches its play button.
            for (int i = 0; i < n; i++) {
                View it = appList.getChildAt(i);
                it.setNextFocusDownId(np ? nowPlaying.playButton.getId() : it.getId());
            }
            for (View b : npButtons) {
                b.setNextFocusUpId(appTarget.getId());
                b.setNextFocusDownId(b.getId());
            }
            nowPlaying.prevButton.setNextFocusLeftId(nowPlaying.prevButton.getId());
            int lift = np ? dp(70) : 0;
            if (lift != dockLift && dockPanel != null) {
                dockLift = lift;
                if (!uiHidden) {
                    dockPanel.animate().translationY(-lift).setDuration(300).start();
                    artChip.animate().translationY(-lift).setDuration(300).start();
                }
            }
        } else {
            searchButton.setNextFocusUpId(np ? nowPlaying.playButton.getId() : searchButton.getId());
            for (View b : npButtons) {
                b.setNextFocusDownId(searchButton.getId());
                b.setNextFocusUpId(b.getId());
            }
            nowPlaying.prevButton.setNextFocusLeftId(appTarget.getId());
        }
    }

    /** Long-press / Menu on an app: move it or remove it. */
    private void showDockMenu(final Apps.Entry app) {
        boolean dock = Prefs.LAYOUT_DOCK.equals(builtLayout);
        final String[] items = {dock ? "Move left" : "Move up", dock ? "Move right" : "Move down",
                "Move to start", "Remove from home"};
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle(app.label)
                .setItems(items, (d, which) -> {
                    List<String> order = new ArrayList<>(Apps.dockKeys(this, prefs, Apps.all(this, prefs)));
                    int idx = order.indexOf(app.key);
                    if (which == 0 && idx > 0) Collections.swap(order, idx, idx - 1);
                    if (which == 1 && idx >= 0 && idx < order.size() - 1) Collections.swap(order, idx, idx + 1);
                    if (which == 2 && idx > 0) {
                        order.remove(idx);
                        order.add(0, app.key);
                    }
                    if (which == 3) order.remove(app.key);
                    prefs.setAppOrder(order);
                    dockSig = "";
                    refreshDock();
                })
                .show();
    }

    private void openArtMode() {
        Intent i = getPackageManager().getLeanbackLaunchIntentForPackage("com.tcl.exhibit");
        if (i != null) safeStart(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    }

    // ---------------------------------------------------------------- launching

    private void safeStart(Intent intent) {
        try {
            startActivity(intent);
        } catch (ActivityNotFoundException | SecurityException ignored) {
        }
    }

    private void openProfiles() {
        HomeRedirectService.userOpenedChooserAt = SystemClock.elapsedRealtime();
        Intent chooser = new Intent().setComponent(new ComponentName(GOOGLE_TV,
                GOOGLE_TV + ".profile.chooser.ProfileChooserActivity"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            startActivity(chooser);
        } catch (ActivityNotFoundException | SecurityException e) {
            safeStart(new Intent(android.provider.Settings.ACTION_SYNC_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        }
    }

    private void openSearch() {
        openScreen(SearchActivity.class);
    }

    private void openScreen(Class<?> screen) {
        startActivity(new Intent(this, screen));
        Style.enter(this);
    }

    private void openSettings() {
        safeStart(new Intent(android.provider.Settings.ACTION_SETTINGS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    }
}
