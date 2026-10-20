package com.eric.artlauncher;

import android.content.ComponentName;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.drawable.GradientDrawable;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.List;

/**
 * "Now playing" card: shows what Spotify (or any app) is playing in the background, with
 * previous / play-pause / next. Hidden when nothing is playing or paused.
 */
public final class NowPlaying {

    private final Context c;
    private final Handler handler = new Handler(Looper.getMainLooper());
    public final LinearLayout view;
    public final View prevButton, playButton, nextButton, closeButton;
    private final ImageView art;
    private final TextView title, subtitle;
    private final MediaIcon playIcon;
    private MediaSessionManager msm;
    private MediaController controller;
    private Runnable onVisibilityChanged;
    private final GradientDrawable cardBg;
    private final View progressFill;
    private final FrameLayout progressTrack;
    private final LinearLayout textBox;
    private boolean idle = false;
    private long closedAt = 0;
    /** App whose card was dismissed with ✕; it stays hidden until that app plays again. */
    private String dismissedPkg = null;
    /** Re-checks the sessions now and then, so the card can never get stuck hidden or shown. */
    private final Runnable watchdog = new Runnable() {
        @Override
        public void run() {
            if (msm != null) pick();
            handler.postDelayed(this, 5000);
        }
    };
    private RimDrawable rim;

    /** Uses the same outline as the dock (gradient-coloured during the carousel). */
    public void setRim(GradientField field) {
        rim = new RimDrawable(view, field, dp(22), dp(1.5f));
        cardBg.setStroke(0, 0);
        view.setForeground(rim);
    }
    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            updateProgress();
            handler.postDelayed(this, 1000);
        }
    };

    private final MediaSessionManager.OnActiveSessionsChangedListener sessionsListener = list -> pick();
    private final MediaController.Callback callback = new MediaController.Callback() {
        @Override public void onPlaybackStateChanged(PlaybackState state) { handler.post(NowPlaying.this::render); }
        @Override public void onMetadataChanged(MediaMetadata metadata) { handler.post(NowPlaying.this::render); }
        @Override public void onSessionDestroyed() { handler.post(NowPlaying.this::pick); }
    };

    public NowPlaying(Context c) {
        this.c = c;
        view = new LinearLayout(c);
        view.setOrientation(LinearLayout.HORIZONTAL);
        view.setGravity(Gravity.CENTER_VERTICAL);
        view.setPadding(dp(10), dp(10), dp(12), dp(10));
        cardBg = new GradientDrawable();
        cardBg.setCornerRadius(dp(22));
        cardBg.setColor(0x80202020);
        cardBg.setStroke(dp(1), 0x26FFFFFF);
        view.setBackground(cardBg);
        view.setVisibility(View.GONE);

        art = new ImageView(c);
        art.setScaleType(ImageView.ScaleType.CENTER_CROP);
        final int r = dp(10);
        art.setOutlineProvider(new ViewOutlineProvider() {
            @Override public void getOutline(View v, Outline o) { o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), r); }
        });
        art.setClipToOutline(true);
        art.setBackgroundColor(0x33FFFFFF);
        view.addView(art, new LinearLayout.LayoutParams(dp(52), dp(52)));

        LinearLayout text = new LinearLayout(c);
        textBox = text;
        text.setOrientation(LinearLayout.VERTICAL);
        text.setPadding(dp(12), 0, dp(10), 0);
        title = new TextView(c);
        title.setTextColor(Color.WHITE);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        title.setSingleLine(true);
        title.setEllipsize(android.text.TextUtils.TruncateAt.END);
        subtitle = new TextView(c);
        subtitle.setTextColor(0xB3FFFFFF);
        subtitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        subtitle.setSingleLine(true);
        subtitle.setEllipsize(android.text.TextUtils.TruncateAt.END);
        text.addView(title);
        text.addView(subtitle);
        // Thin progress bar under the track name.
        progressTrack = new FrameLayout(c);
        GradientDrawable trackBg = new GradientDrawable();
        trackBg.setCornerRadius(dp(2));
        trackBg.setColor(0x33FFFFFF);
        progressTrack.setBackground(trackBg);
        progressFill = new View(c);
        GradientDrawable fillBg = new GradientDrawable();
        fillBg.setCornerRadius(dp(2));
        fillBg.setColor(0xE6FFFFFF);
        progressFill.setBackground(fillBg);
        progressFill.setPivotX(0);
        progressFill.setScaleX(0);
        progressTrack.addView(progressFill, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        LinearLayout.LayoutParams ptLp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(3));
        ptLp.topMargin = dp(8);
        text.addView(progressTrack, ptLp);
        view.addView(text, new LinearLayout.LayoutParams(dp(190), LinearLayout.LayoutParams.WRAP_CONTENT));

        prevButton = button(new MediaIcon(MediaIcon.PREV), "Previous", () -> {
            if (controller != null) controller.getTransportControls().skipToPrevious();
        });
        playIcon = new MediaIcon(MediaIcon.PAUSE);
        playButton = button(playIcon, "Play or pause", () -> {
            if (controller == null) return;
            PlaybackState s = controller.getPlaybackState();
            if (s != null && s.getState() == PlaybackState.STATE_PLAYING) controller.getTransportControls().pause();
            else controller.getTransportControls().play();
        });
        nextButton = button(new MediaIcon(MediaIcon.NEXT), "Next", () -> {
            if (controller != null) controller.getTransportControls().skipToNext();
        });
        closeButton = button(new MediaIcon(MediaIcon.CLOSE), "Stop and close", this::stopAndClose);
        for (View b : new View[]{prevButton, playButton, nextButton, closeButton}) {
            b.setId(View.generateViewId());
            view.addView(b);
        }
        prevButton.setNextFocusRightId(playButton.getId());
        playButton.setNextFocusLeftId(prevButton.getId());
        playButton.setNextFocusRightId(nextButton.getId());
        nextButton.setNextFocusLeftId(playButton.getId());
        nextButton.setNextFocusRightId(closeButton.getId());
        closeButton.setNextFocusLeftId(nextButton.getId());
        closeButton.setNextFocusRightId(closeButton.getId());
        Style.setFont(title, Style.medium(c));
        Style.setFont(subtitle, Style.regular(c));
    }

    public void setOnVisibilityChanged(Runnable r) {
        onVisibilityChanged = r;
    }

    private int dp(float v) {
        return Tiles.dp(c, v);
    }

    private View button(final MediaIcon icon, String label, Runnable action) {
        FrameLayout b = new FrameLayout(c);
        final GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.OVAL);
        bg.setColor(Color.TRANSPARENT);
        b.setBackground(bg);
        b.setFocusable(true);
        b.setFocusableInTouchMode(true);
        b.setContentDescription(label);
        ImageView iv = new ImageView(c);
        iv.setImageDrawable(icon);
        b.addView(iv, new FrameLayout.LayoutParams(dp(20), dp(20), Gravity.CENTER));
        b.setOnFocusChangeListener((v, f) -> {
            bg.setColor(f ? 0xF2FFFFFF : Color.TRANSPARENT);
            icon.setColor(f ? 0xFF111111 : Color.WHITE);
        });
        b.setOnClickListener(v -> action.run());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(42), dp(42));
        lp.leftMargin = dp(2);
        b.setLayoutParams(lp);
        return b;
    }

    public void start() {
        try {
            msm = (MediaSessionManager) c.getSystemService(Context.MEDIA_SESSION_SERVICE);
            ComponentName me = new ComponentName(c, MediaListener.class);
            msm.addOnActiveSessionsChangedListener(sessionsListener, me, handler);
            pick();
            handler.removeCallbacks(watchdog);
            handler.postDelayed(watchdog, 5000);
        } catch (SecurityException e) {
            // Notification access not granted yet; the card stays hidden.
            view.setVisibility(View.GONE);
        }
    }

    public void stop() {
        if (msm != null) {
            try {
                msm.removeOnActiveSessionsChangedListener(sessionsListener);
            } catch (Exception ignored) {
            }
        }
        if (controller != null) controller.unregisterCallback(callback);
        controller = null;
        handler.removeCallbacks(tick);
        handler.removeCallbacks(watchdog);
    }

    /** Prefer a session that is playing; otherwise one that is paused. */
    private void pick() {
        List<MediaController> list;
        try {
            list = msm.getActiveSessions(new ComponentName(c, MediaListener.class));
        } catch (Exception e) {
            return;
        }
        MediaController best = null;
        for (MediaController mc : list) {
            PlaybackState s = mc.getPlaybackState();
            if (s == null) continue;
            if (s.getState() == PlaybackState.STATE_PLAYING) {
                best = mc;
                break;
            }
            if (s.getState() == PlaybackState.STATE_PAUSED && best == null) best = mc;
        }
        if (controller != null && (best == null || !controller.getSessionToken().equals(best.getSessionToken()))) {
            controller.unregisterCallback(callback);
        }
        if (best != null && (controller == null || !controller.getSessionToken().equals(best.getSessionToken()))) {
            best.registerCallback(callback, handler);
        }
        controller = best;
        render();
    }

    private void render() {
        boolean wasVisible = view.getVisibility() == View.VISIBLE;
        PlaybackState s = controller == null ? null : controller.getPlaybackState();
        MediaMetadata m = controller == null ? null : controller.getMetadata();
        // After ✕ the card stays away until that app actually starts playing again
        // (apps like Spotify keep a paused session alive instead of closing).
        boolean justClosed = android.os.SystemClock.elapsedRealtime() - closedAt < 3000;
        if (controller != null && controller.getPackageName().equals(dismissedPkg)) {
            boolean playingAgain = s != null && s.getState() == PlaybackState.STATE_PLAYING && !justClosed;
            if (playingAgain) dismissedPkg = null;
        }
        boolean dismissed = controller != null && controller.getPackageName().equals(dismissedPkg);
        boolean show = s != null && !justClosed && !dismissed
                && (s.getState() == PlaybackState.STATE_PLAYING || s.getState() == PlaybackState.STATE_PAUSED
                || s.getState() == PlaybackState.STATE_BUFFERING);
        if (show) {
            String t = m == null ? null : m.getString(MediaMetadata.METADATA_KEY_TITLE);
            if (t == null && m != null) t = m.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE);
            String a = m == null ? null : m.getString(MediaMetadata.METADATA_KEY_ARTIST);
            if (a == null && m != null) a = m.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE);
            title.setText(t == null ? appName() : t);
            subtitle.setText(a == null ? (t == null ? "Playing" : appName()) : a + " · " + appName());
            Bitmap b = m == null ? null : m.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART);
            if (b == null && m != null) b = m.getBitmap(MediaMetadata.METADATA_KEY_ART);
            if (b == null && m != null) b = m.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON);
            if (b != null) {
                art.setImageBitmap(b);
                cardBg.setColor(Style.withAlpha(Style.blend(Style.muted(b), 0xFF000000, 0.25f), 0xC0));
            }
            else {
                try {
                    art.setImageDrawable(c.getPackageManager().getApplicationIcon(controller.getPackageName()));
                } catch (Exception e) {
                    art.setImageDrawable(null);
                }
            }
            playIcon.setKind(s.getState() == PlaybackState.STATE_PLAYING ? MediaIcon.PAUSE : MediaIcon.PLAY);
            long actions = s.getActions();
            int prevVis = (actions & PlaybackState.ACTION_SKIP_TO_PREVIOUS) != 0 ? View.VISIBLE : View.GONE;
            int nextVis = (actions & PlaybackState.ACTION_SKIP_TO_NEXT) != 0 ? View.VISIBLE : View.GONE;
            // In idle music mode the controls stay hidden; remember what to restore.
            prevButton.setTag(R.id.font_tag, prevVis);
            nextButton.setTag(R.id.font_tag, nextVis);
            if (!idle) {
                prevButton.setVisibility(prevVis);
                nextButton.setVisibility(nextVis);
            }
        }
        view.setVisibility(show ? View.VISIBLE : View.GONE);
        handler.removeCallbacks(tick);
        if (show) handler.post(tick);
        if (wasVisible != show && onVisibilityChanged != null) onVisibilityChanged.run();
    }

    /** Stops playback, hides the card and closes the app if it's running in the background. */
    private void stopAndClose() {
        if (controller == null) return;
        final String pkg = controller.getPackageName();
        dismissedPkg = pkg;
        try {
            controller.getTransportControls().stop();
        } catch (Exception ignored) {
        }
        closedAt = android.os.SystemClock.elapsedRealtime();
        view.setVisibility(View.GONE);
        if (onVisibilityChanged != null) onVisibilityChanged.run();
        handler.postDelayed(this::pick, 3500);
        handler.postDelayed(() -> {
            try {
                ((android.app.ActivityManager) c.getSystemService(Context.ACTIVITY_SERVICE))
                        .killBackgroundProcesses(pkg);
            } catch (Exception ignored) {
            }
        }, 800);
    }

    private void updateProgress() {
        if (controller == null) return;
        PlaybackState s = controller.getPlaybackState();
        MediaMetadata m = controller.getMetadata();
        long dur = m == null ? 0 : m.getLong(MediaMetadata.METADATA_KEY_DURATION);
        if (s == null || dur <= 0) {
            progressTrack.setVisibility(View.GONE);
            return;
        }
        long pos = s.getPosition();
        if (s.getState() == PlaybackState.STATE_PLAYING) {
            pos += (long) ((android.os.SystemClock.elapsedRealtime() - s.getLastPositionUpdateTime()) * s.getPlaybackSpeed());
        }
        progressTrack.setVisibility(View.VISIBLE);
        progressFill.setScaleX(Math.max(0f, Math.min(1f, pos / (float) dur)));
    }

    /**
     * Idle "music mode": while only the artwork is showing, the card grows into a larger
     * album-art panel (controls hide; the first key press brings the full home screen back).
     */
    public void setIdle(boolean idle) {
        if (this.idle == idle) return;
        this.idle = idle;
        if (view.getParent() instanceof android.view.ViewGroup) {
            android.transition.TransitionManager.beginDelayedTransition((android.view.ViewGroup) view.getParent(),
                    new android.transition.AutoTransition().setDuration(600));
        }
        int artSize = dp(idle ? 76 : 52);
        art.getLayoutParams().width = artSize;
        art.getLayoutParams().height = artSize;
        textBox.getLayoutParams().width = dp(idle ? 230 : 190);
        textBox.setPadding(dp(idle ? 16 : 12), 0, dp(10), 0);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, idle ? 18 : 15);
        subtitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, idle ? 14 : 13);
        int pad = dp(idle ? 12 : 10);
        view.setPadding(pad, pad, dp(idle ? 18 : 12), pad);
        cardBg.setCornerRadius(dp(idle ? 26 : 22));
        if (rim != null) rim.setRadius(dp(idle ? 26 : 22));
        for (View b : new View[]{prevButton, playButton, nextButton, closeButton}) {
            if (idle) b.setTag(R.id.font_tag, b.getVisibility());
            b.setVisibility(idle ? View.GONE : visibilityBeforeIdle(b));
        }
        view.requestLayout();
    }

    private int visibilityBeforeIdle(View b) {
        Object v = b.getTag(R.id.font_tag);
        return v instanceof Integer ? (Integer) v : View.VISIBLE;
    }

    private String appName() {
        try {
            return String.valueOf(c.getPackageManager().getApplicationLabel(
                    c.getPackageManager().getApplicationInfo(controller.getPackageName(), 0)));
        } catch (Exception e) {
            return "";
        }
    }

    /** Play / pause / previous / next glyphs. */
    static final class MediaIcon extends android.graphics.drawable.Drawable {
        static final int PLAY = 0, PAUSE = 1, PREV = 2, NEXT = 3, CLOSE = 4;
        private int kind;
        private final android.graphics.Paint p = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);

        MediaIcon(int kind) {
            this.kind = kind;
            p.setColor(Color.WHITE);
            p.setPathEffect(new android.graphics.CornerPathEffect(2));
        }

        void setKind(int k) { kind = k; invalidateSelf(); }

        void setColor(int color) { p.setColor(color); invalidateSelf(); }

        @Override
        public void draw(android.graphics.Canvas cv) {
            android.graphics.Rect b = getBounds();
            float s = Math.min(b.width(), b.height()), x = b.exactCenterX() - s / 2, y = b.exactCenterY() - s / 2;
            android.graphics.Path path = new android.graphics.Path();
            switch (kind) {
                case PLAY:
                    path.moveTo(x + s * 0.24f, y + s * 0.12f);
                    path.lineTo(x + s * 0.88f, y + s * 0.5f);
                    path.lineTo(x + s * 0.24f, y + s * 0.88f);
                    path.close();
                    cv.drawPath(path, p);
                    break;
                case PAUSE:
                    cv.drawRoundRect(x + s * 0.2f, y + s * 0.14f, x + s * 0.42f, y + s * 0.86f, s * 0.05f, s * 0.05f, p);
                    cv.drawRoundRect(x + s * 0.58f, y + s * 0.14f, x + s * 0.8f, y + s * 0.86f, s * 0.05f, s * 0.05f, p);
                    break;
                case CLOSE: {
                    p.setStyle(android.graphics.Paint.Style.STROKE);
                    p.setStrokeWidth(s * 0.12f);
                    p.setStrokeCap(android.graphics.Paint.Cap.ROUND);
                    cv.drawLine(x + s * 0.24f, y + s * 0.24f, x + s * 0.76f, y + s * 0.76f, p);
                    cv.drawLine(x + s * 0.76f, y + s * 0.24f, x + s * 0.24f, y + s * 0.76f, p);
                    p.setStyle(android.graphics.Paint.Style.FILL);
                    break;
                }
                case PREV:
                case NEXT: {
                    boolean next = kind == NEXT;
                    float dir = next ? 1 : -1, cx = x + s / 2;
                    for (int i = 0; i < 2; i++) {
                        float base = cx + dir * (-s * 0.38f + i * s * 0.34f);
                        path.reset();
                        path.moveTo(base, y + s * 0.18f);
                        path.lineTo(base + dir * s * 0.34f, y + s * 0.5f);
                        path.lineTo(base, y + s * 0.82f);
                        path.close();
                        cv.drawPath(path, p);
                    }
                    break;
                }
                default:
            }
        }

        @Override public void setAlpha(int a) { p.setAlpha(a); }
        @Override public void setColorFilter(android.graphics.ColorFilter cf) { p.setColorFilter(cf); }
        @Override public int getOpacity() { return android.graphics.PixelFormat.TRANSLUCENT; }
    }
}
