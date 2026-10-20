package com.eric.artlauncher;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.PorterDuffXfermode;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.AdaptiveIconDrawable;
import android.graphics.drawable.Drawable;

/**
 * White single-colour app logos for the minimal layouts. Popular apps get a hand-drawn
 * glyph; anything else uses its own icon turned white.
 */
public final class Logos {

    private Logos() {
    }

    public static Drawable mono(Apps.Entry e) {
        switch (e.key) {
            case "com.google.android.youtube.tv": return new Glyph(Glyph.YOUTUBE);
            case "com.google.android.youtube.tvmusic": return new Glyph(Glyph.YT_MUSIC);
            case "com.netflix.ninja": return new Glyph(Glyph.NETFLIX);
            case "com.stremio.one": return new Glyph(Glyph.STREMIO);
            case "com.spotify.tv.android": return new Glyph(Glyph.SPOTIFY);
            case "com.amazon.amazonvideo.livingroom": return new Glyph(Glyph.PRIME);
            case "com.android.vending": return new Glyph(Glyph.PLAY);
            default:
        }
        Drawable src = e.icon;
        if (src == null) return new Glyph(Glyph.DOTS);
        Boolean ok = SILHOUETTE_OK.get(e.key);
        Drawable white;
        // Tint a separate copy, so the original stays in colour for the fallback.
        Drawable copy = src.getConstantState() != null ? src.getConstantState().newDrawable().mutate() : src;
        if (copy instanceof AdaptiveIconDrawable && ((AdaptiveIconDrawable) copy).getForeground() != null) {
            white = new Tinted(((AdaptiveIconDrawable) copy).getForeground().mutate(), 0.25f);
        } else {
            white = new Tinted(copy.mutate(), 0f);
        }
        if (ok == null) {
            ok = !isBlob(white);
            SILHOUETTE_OK.put(e.key, ok);
        }
        // Icons that are solid shapes turn into plain white blobs; show those in colour instead.
        return ok ? white : rounded(e.icon);
    }

    private static final java.util.Map<String, Boolean> SILHOUETTE_OK = new java.util.HashMap<>();

    /** True if the white version fills most of its own bounding box (square / circle blob). */
    private static boolean isBlob(Drawable white) {
        int n = 48;
        android.graphics.Bitmap b = android.graphics.Bitmap.createBitmap(n, n, android.graphics.Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        white.setBounds(0, 0, n, n);
        white.draw(c);
        int minX = n, minY = n, maxX = -1, maxY = -1, opaque = 0;
        for (int y = 0; y < n; y++) for (int x = 0; x < n; x++) {
            if (Color.alpha(b.getPixel(x, y)) > 128) {
                opaque++;
                minX = Math.min(minX, x); maxX = Math.max(maxX, x);
                minY = Math.min(minY, y); maxY = Math.max(maxY, y);
            }
        }
        b.recycle();
        if (maxX < 0) return true;
        float box = (maxX - minX + 1) * (maxY - minY + 1);
        return opaque / box > 0.7f;
    }

    /** The app's own colour icon, clipped to a rounded square. */
    private static Drawable rounded(final Drawable icon) {
        return new Drawable() {
            @Override
            public void draw(Canvas c) {
                Rect b = getBounds();
                Path clip = new Path();
                clip.addRoundRect(new RectF(b), b.width() * 0.24f, b.height() * 0.24f, Path.Direction.CW);
                c.save();
                c.clipPath(clip);
                icon.setBounds(b);
                icon.draw(c);
                c.restore();
            }
            @Override public void setAlpha(int a) { icon.setAlpha(a); }
            @Override public void setColorFilter(ColorFilter cf) { }
            @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
        };
    }

    /** Draws a drawable in solid white, optionally cropping the adaptive-icon safe margin. */
    static final class Tinted extends Drawable {
        private final Drawable d;
        private final float crop;

        Tinted(Drawable d, float crop) {
            this.d = d;
            this.crop = crop;
            d.setColorFilter(new PorterDuffColorFilter(Color.WHITE, PorterDuff.Mode.SRC_IN));
        }

        @Override
        public void draw(Canvas c) {
            Rect b = getBounds();
            int dx = Math.round(b.width() * crop), dy = Math.round(b.height() * crop);
            d.setBounds(b.left - dx, b.top - dy, b.right + dx, b.bottom + dy);
            d.draw(c);
        }

        @Override public void setAlpha(int a) { d.setAlpha(a); }
        @Override public void setColorFilter(ColorFilter cf) { }
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }

    static final class Glyph extends Drawable {
        static final int YOUTUBE = 0, NETFLIX = 1, STREMIO = 2, SPOTIFY = 3, PRIME = 4, PLAY = 5,
                YT_MUSIC = 6, DOTS = 7;
        private final int kind;
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint clear = new Paint(Paint.ANTI_ALIAS_FLAG);

        Glyph(int kind) {
            this.kind = kind;
            p.setColor(Color.WHITE);
            clear.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.CLEAR));
        }

        @Override
        public void draw(Canvas c) {
            Rect b = getBounds();
            float s = Math.min(b.width(), b.height());
            float x = b.exactCenterX() - s / 2, y = b.exactCenterY() - s / 2;
            int layer = c.saveLayer(b.left, b.top, b.right, b.bottom, null);
            p.setStyle(Paint.Style.FILL);
            switch (kind) {
                case YOUTUBE: {
                    RectF r = new RectF(x + s * 0.06f, y + s * 0.2f, x + s * 0.94f, y + s * 0.8f);
                    c.drawRoundRect(r, s * 0.16f, s * 0.16f, p);
                    c.drawPath(tri(x + s * 0.41f, y + s * 0.36f, x + s * 0.41f, y + s * 0.64f, x + s * 0.65f, y + s * 0.5f), clear);
                    break;
                }
                case YT_MUSIC: {
                    c.drawCircle(x + s / 2, y + s / 2, s * 0.44f, p);
                    c.drawCircle(x + s / 2, y + s / 2, s * 0.28f, clear);
                    c.drawCircle(x + s / 2, y + s / 2, s * 0.24f, p);
                    c.drawPath(tri(x + s * 0.43f, y + s * 0.39f, x + s * 0.43f, y + s * 0.61f, x + s * 0.61f, y + s * 0.5f), clear);
                    break;
                }
                case NETFLIX: {
                    float w = s * 0.17f, l = x + s * 0.24f, r = x + s * 0.76f, t = y + s * 0.08f, bt = y + s * 0.92f;
                    c.drawRect(l, t, l + w, bt, p);
                    c.drawRect(r - w, t, r, bt, p);
                    Path diag = new Path();
                    diag.moveTo(l, t);
                    diag.lineTo(l + w, t);
                    diag.lineTo(r, bt);
                    diag.lineTo(r - w, bt);
                    diag.close();
                    c.drawPath(diag, p);
                    break;
                }
                case STREMIO: {
                    Path d = new Path();
                    float cx = x + s / 2, cy = y + s / 2, h = s * 0.44f;
                    d.moveTo(cx, cy - h);
                    d.lineTo(cx + h, cy);
                    d.lineTo(cx, cy + h);
                    d.lineTo(cx - h, cy);
                    d.close();
                    p.setPathEffect(new android.graphics.CornerPathEffect(s * 0.08f));
                    c.drawPath(d, p);
                    p.setPathEffect(null);
                    c.drawPath(tri(cx - s * 0.08f, cy - s * 0.14f, cx - s * 0.08f, cy + s * 0.14f, cx + s * 0.15f, cy), clear);
                    break;
                }
                case SPOTIFY: {
                    float cx = x + s / 2, cy = y + s / 2;
                    c.drawCircle(cx, cy, s * 0.46f, p);
                    clear.setStyle(Paint.Style.STROKE);
                    clear.setStrokeCap(Paint.Cap.ROUND);
                    float[][] arcs = {{0.30f, 0.07f, -0.12f}, {0.24f, 0.06f, 0.04f}, {0.18f, 0.05f, 0.18f}};
                    for (float[] a : arcs) {
                        clear.setStrokeWidth(s * a[1]);
                        float half = s * a[0];
                        RectF r = new RectF(cx - half * 1.2f, cy + s * a[2], cx + half * 1.2f, cy + s * a[2] + half * 1.4f);
                        c.drawArc(r, 200, 140, false, clear);
                    }
                    clear.setStyle(Paint.Style.FILL);
                    break;
                }
                case PRIME: {
                    p.setStyle(Paint.Style.STROKE);
                    p.setStrokeCap(Paint.Cap.ROUND);
                    p.setStrokeWidth(s * 0.07f);
                    RectF r = new RectF(x + s * 0.06f, y + s * 0.2f, x + s * 0.86f, y + s * 0.64f);
                    c.drawArc(r, 25, 130, false, p);
                    float ex = x + s * 0.80f, ey = y + s * 0.6f;
                    c.drawLine(ex, ey, ex - s * 0.13f, ey + s * 0.02f, p);
                    c.drawLine(ex, ey, ex + s * 0.01f, ey - s * 0.12f, p);
                    break;
                }
                case PLAY: {
                    p.setStyle(Paint.Style.STROKE);
                    p.setStrokeJoin(Paint.Join.ROUND);
                    p.setStrokeWidth(s * 0.08f);
                    c.drawPath(tri(x + s * 0.22f, y + s * 0.1f, x + s * 0.22f, y + s * 0.9f, x + s * 0.88f, y + s * 0.5f), p);
                    break;
                }
                default: {
                    float gap = s * 0.28f, r = s * 0.075f;
                    for (int i = -1; i <= 1; i++) for (int j = -1; j <= 1; j++)
                        c.drawCircle(x + s / 2 + i * gap, y + s / 2 + j * gap, r, p);
                }
            }
            c.restoreToCount(layer);
        }

        private static Path tri(float ax, float ay, float bx, float by, float cx, float cy) {
            Path t = new Path();
            t.moveTo(ax, ay);
            t.lineTo(bx, by);
            t.lineTo(cx, cy);
            t.close();
            return t;
        }

        @Override public void setAlpha(int a) { p.setAlpha(a); }
        @Override public void setColorFilter(ColorFilter cf) { p.setColorFilter(cf); }
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }
}
