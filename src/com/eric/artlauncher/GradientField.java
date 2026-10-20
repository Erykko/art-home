package com.eric.artlauncher;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.view.View;
import android.view.animation.LinearInterpolator;

/**
 * Slowly drifting soft colour blobs (a "mesh gradient") made from the colours of the photos
 * on screen. New colours blend in over a couple of seconds.
 */
public class GradientField extends View {

    private static final int BLOBS = 4;
    private final int[] from = new int[BLOBS], to = new int[BLOBS], now = new int[BLOBS];
    private final float[] phase = new float[BLOBS];
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private float blend = 1f;
    private float time = 0f;
    private long lastTick = -1000;
    private final ValueAnimator clock;
    /** When set, this view draws a copy of another field (same colours and motion). */
    private GradientField source;
    private final java.util.List<GradientField> mirrors = new java.util.ArrayList<>();
    private final java.util.List<View> listeners = new java.util.ArrayList<>();

    /** Views redrawn whenever the gradient moves (e.g. the glowing dock outline). */
    public void addListener(View v) {
        listeners.add(v);
    }

    /** A mirror that draws exactly what {@code src} draws, e.g. behind frosted glass. */
    public GradientField(Context c, GradientField src) {
        this(c);
        source = src;
        src.mirrors.add(this);
    }

    public GradientField(Context c) {
        super(c);
        for (int i = 0; i < BLOBS; i++) {
            from[i] = to[i] = now[i] = 0xFF2A2A2A;
            phase[i] = i * 1.7f;
        }
        clock = ValueAnimator.ofFloat(0f, 1f);
        clock.setDuration(60_000);
        clock.setRepeatCount(ValueAnimator.INFINITE);
        clock.setInterpolator(new LinearInterpolator());
        clock.addUpdateListener(a -> {
            // Slow motion looks smoothest at a steady ~30 updates per second.
            long ms = a.getCurrentPlayTime();
            if (ms - lastTick < 32) return;
            lastTick = ms;
            time = (float) (ms / 1000.0);
            invalidate();
            for (GradientField m : mirrors) m.invalidate();
            for (View l : listeners) l.invalidate();
        });
    }

    /** Blend to new colours (one per blob; fewer are repeated). */
    public void setColors(int[] colors) {
        if (colors == null || colors.length == 0) return;
        for (int i = 0; i < BLOBS; i++) {
            from[i] = now[i];
            int c = colors[i % colors.length];
            float[] hsv = new float[3];
            Color.colorToHSV(c, hsv);
            hsv[1] = Math.min(1f, hsv[1] * 1.35f + 0.08f); // richer than the photo average
            hsv[2] = Math.min(Math.max(hsv[2] * 1.3f, 0.35f), 0.7f);
            to[i] = Color.HSVToColor(hsv);
        }
        ValueAnimator va = ValueAnimator.ofFloat(0f, 1f);
        va.setDuration(2500);
        va.addUpdateListener(a -> blend = (float) a.getAnimatedValue());
        va.start();
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (source == null) clock.start();
    }

    @Override
    protected void onDetachedFromWindow() {
        clock.cancel();
        super.onDetachedFromWindow();
    }

    @Override
    public void setVisibility(int v) {
        super.setVisibility(v);
        if (source != null) return;
        if (v == VISIBLE) { if (!clock.isStarted()) clock.start(); } else clock.cancel();
    }

    // The gradient has no fine detail, so it is drawn into a tiny bitmap once per frame and
    // scaled up with filtering. Mirrors reuse the same bitmap.
    private static final int SMALL_W = 64, SMALL_H = 36;
    private android.graphics.Bitmap small;
    private Canvas smallCanvas;
    private long renderedFrame = -1;
    private final Paint scalePaint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.DITHER_FLAG);
    private final android.graphics.Rect dst = new android.graphics.Rect();

    /** Current frame of the gradient as a tiny bitmap covering the whole screen. */
    public android.graphics.Bitmap frame() {
        if (small == null) {
            small = android.graphics.Bitmap.createBitmap(SMALL_W, SMALL_H, android.graphics.Bitmap.Config.ARGB_8888);
            smallCanvas = new Canvas(small);
        }
        long frameId = Math.round(time * 1000);
        if (frameId == renderedFrame) return small;
        renderedFrame = frameId;
        Canvas c = smallCanvas;
        int w = SMALL_W, h = SMALL_H;
        c.drawColor(0xFF0E0E10);
        float r = Math.max(w, h) * 0.62f;
        for (int i = 0; i < BLOBS; i++) {
            now[i] = Style.blend(from[i], to[i], blend);
            // Each blob wanders on its own slow Lissajous path (time-based, so dropped frames
            // never cause jumps).
            float t = time * 0.14f + phase[i];
            float cx = w * (0.5f + 0.42f * (float) Math.sin(t * (1.0f + i * 0.13f)));
            float cy = h * (0.5f + 0.38f * (float) Math.cos(t * (0.8f + i * 0.17f)));
            paint.setShader(new RadialGradient(cx, cy, r,
                    new int[]{Style.withAlpha(now[i], 0xD0), Style.withAlpha(now[i], 0x00)},
                    null, Shader.TileMode.CLAMP));
            c.drawCircle(cx, cy, r, paint);
        }
        c.drawColor(0x30000000);
        return small;
    }

    @Override
    protected void onDraw(Canvas c) {
        GradientField f = source != null ? source : this;
        dst.set(0, 0, getWidth(), getHeight());
        c.drawBitmap(f.frame(), null, dst, scalePaint);
    }
}
