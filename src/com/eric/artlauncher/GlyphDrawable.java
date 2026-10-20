package com.eric.artlauncher;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;

/** Simple line icons (profile, settings) drawn in code so the APK needs no icon assets. */
public class GlyphDrawable extends Drawable {

    public static final int PROFILE = 0;
    public static final int SETTINGS = 1;
    public static final int FRAME = 2;
    public static final int SEARCH = 3;
    public static final int MIC = 4;
    public static final int GRID = 5;

    private final int glyph;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

    public GlyphDrawable(int glyph, int color) {
        this.glyph = glyph;
        paint.setColor(color);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeJoin(Paint.Join.ROUND);
    }

    public void setColor(int color) {
        paint.setColor(color);
        invalidateSelf();
    }

    @Override
    public void draw(Canvas c) {
        float w = getBounds().width(), h = getBounds().height();
        float s = Math.min(w, h);
        float cx = getBounds().left + w / 2f, cy = getBounds().top + h / 2f;
        paint.setStrokeWidth(s * 0.08f);
        if (glyph == PROFILE) {
            c.drawCircle(cx, cy - s * 0.14f, s * 0.17f, paint);
            RectF body = new RectF(cx - s * 0.32f, cy + s * 0.12f, cx + s * 0.32f, cy + s * 0.68f);
            c.drawArc(body, 180, 180, false, paint);
        } else if (glyph == GRID) {
            float g = s * 0.1f, q = s * 0.26f;
            for (int i = 0; i < 2; i++) for (int j = 0; j < 2; j++) {
                float l = cx - g / 2 - q + i * (q + g), t = cy - g / 2 - q + j * (q + g);
                c.drawRoundRect(new RectF(l, t, l + q, t + q), s * 0.06f, s * 0.06f, paint);
            }
        } else if (glyph == SEARCH) {
            c.drawCircle(cx - s * 0.06f, cy - s * 0.06f, s * 0.22f, paint);
            c.drawLine(cx + s * 0.1f, cy + s * 0.1f, cx + s * 0.3f, cy + s * 0.3f, paint);
        } else if (glyph == MIC) {
            RectF capsule = new RectF(cx - s * 0.12f, cy - s * 0.36f, cx + s * 0.12f, cy + s * 0.08f);
            c.drawRoundRect(capsule, s * 0.12f, s * 0.12f, paint);
            RectF arc = new RectF(cx - s * 0.24f, cy - s * 0.2f, cx + s * 0.24f, cy + s * 0.2f);
            c.drawArc(arc, 0, 180, false, paint);
            c.drawLine(cx, cy + s * 0.2f, cx, cy + s * 0.34f, paint);
        } else if (glyph == FRAME) {
            // Picture frame with a mountain and sun.
            RectF frame = new RectF(cx - s * 0.38f, cy - s * 0.3f, cx + s * 0.38f, cy + s * 0.3f);
            c.drawRoundRect(frame, s * 0.06f, s * 0.06f, paint);
            Path hills = new Path();
            hills.moveTo(cx - s * 0.3f, cy + s * 0.2f);
            hills.lineTo(cx - s * 0.08f, cy - s * 0.04f);
            hills.lineTo(cx + s * 0.06f, cy + s * 0.1f);
            hills.lineTo(cx + s * 0.16f, cy + s * 0.02f);
            hills.lineTo(cx + s * 0.3f, cy + s * 0.2f);
            c.drawPath(hills, paint);
            c.drawCircle(cx + s * 0.17f, cy - s * 0.13f, s * 0.05f, paint);
        } else {
            float rOuter = s * 0.36f, rInner = s * 0.27f;
            Path gear = new Path();
            int teeth = 8;
            for (int i = 0; i < teeth * 2; i++) {
                double a0 = Math.PI * 2 * i / (teeth * 2);
                double a1 = Math.PI * 2 * (i + 1) / (teeth * 2);
                float r = (i % 2 == 0) ? rOuter : rInner;
                float x0 = cx + (float) Math.cos(a0) * r, y0 = cy + (float) Math.sin(a0) * r;
                float x1 = cx + (float) Math.cos(a1) * r, y1 = cy + (float) Math.sin(a1) * r;
                if (i == 0) gear.moveTo(x0, y0); else gear.lineTo(x0, y0);
                gear.lineTo(x1, y1);
            }
            gear.close();
            c.drawPath(gear, paint);
            c.drawCircle(cx, cy, s * 0.1f, paint);
        }
    }

    @Override
    public void setAlpha(int alpha) {
        paint.setAlpha(alpha);
    }

    @Override
    public void setColorFilter(ColorFilter cf) {
        paint.setColorFilter(cf);
    }

    @Override
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }
}
