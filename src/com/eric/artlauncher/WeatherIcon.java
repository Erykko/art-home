package com.eric.artlauncher;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;

/** Simple line weather icons drawn in code, matching the other top-right glyphs. */
public class WeatherIcon extends Drawable {

    public static final int SUN = 0, MOON = 1, SUN_CLOUD = 2, MOON_CLOUD = 3, CLOUD = 4,
            RAIN = 5, STORM = 6, SNOW = 7;

    private int kind;
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);

    public WeatherIcon(int kind, int color) {
        this.kind = kind;
        p.setColor(color);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeCap(Paint.Cap.ROUND);
        p.setStrokeJoin(Paint.Join.ROUND);
    }

    public void setKind(int kind) {
        this.kind = kind;
        invalidateSelf();
    }

    @Override
    public void draw(Canvas c) {
        float s = Math.min(getBounds().width(), getBounds().height());
        float x = getBounds().left + (getBounds().width() - s) / 2f, y = getBounds().top + (getBounds().height() - s) / 2f;
        p.setStrokeWidth(s * 0.07f);
        switch (kind) {
            case SUN: sun(c, x + s * 0.5f, y + s * 0.5f, s * 0.2f, true); break;
            case MOON: moon(c, x + s * 0.5f, y + s * 0.5f, s * 0.28f); break;
            case SUN_CLOUD:
                sun(c, x + s * 0.36f, y + s * 0.36f, s * 0.15f, true);
                cloud(c, x + s * 0.12f, y + s * 0.42f, s * 0.8f);
                break;
            case MOON_CLOUD:
                moon(c, x + s * 0.38f, y + s * 0.36f, s * 0.2f);
                cloud(c, x + s * 0.12f, y + s * 0.42f, s * 0.8f);
                break;
            case CLOUD: cloud(c, x + s * 0.08f, y + s * 0.26f, s * 0.86f); break;
            case RAIN:
                cloud(c, x + s * 0.08f, y + s * 0.12f, s * 0.86f);
                for (int i = 0; i < 3; i++) {
                    float lx = x + s * (0.3f + i * 0.2f);
                    c.drawLine(lx, y + s * 0.72f, lx - s * 0.06f, y + s * 0.9f, p);
                }
                break;
            case STORM:
                cloud(c, x + s * 0.08f, y + s * 0.12f, s * 0.86f);
                Path bolt = new Path();
                bolt.moveTo(x + s * 0.55f, y + s * 0.62f);
                bolt.lineTo(x + s * 0.43f, y + s * 0.8f);
                bolt.lineTo(x + s * 0.55f, y + s * 0.8f);
                bolt.lineTo(x + s * 0.45f, y + s * 0.96f);
                c.drawPath(bolt, p);
                break;
            case SNOW:
                cloud(c, x + s * 0.08f, y + s * 0.12f, s * 0.86f);
                for (int i = 0; i < 3; i++) c.drawPoint(x + s * (0.3f + i * 0.2f), y + s * 0.82f, p);
                break;
            default:
        }
    }

    private void sun(Canvas c, float cx, float cy, float r, boolean rays) {
        c.drawCircle(cx, cy, r, p);
        if (!rays) return;
        for (int i = 0; i < 8; i++) {
            double a = Math.PI * i / 4;
            c.drawLine(cx + (float) Math.cos(a) * r * 1.5f, cy + (float) Math.sin(a) * r * 1.5f,
                    cx + (float) Math.cos(a) * r * 2.0f, cy + (float) Math.sin(a) * r * 2.0f, p);
        }
    }

    private void moon(Canvas c, float cx, float cy, float r) {
        Path m = new Path();
        m.addArc(new RectF(cx - r, cy - r, cx + r, cy + r), 60, 250);
        m.arcTo(new RectF(cx - r * 0.35f, cy - r * 1.05f, cx + r * 1.05f, cy + r * 0.45f), 250, -230);
        c.drawPath(m, p);
    }

    /** Cloud occupying width w from (x, y), height about w * 0.5. */
    private void cloud(Canvas c, float x, float y, float w) {
        Path cl = new Path();
        float h = w * 0.5f;
        cl.moveTo(x + w * 0.2f, y + h);
        cl.lineTo(x + w * 0.82f, y + h);
        cl.arcTo(new RectF(x + w * 0.64f, y + h * 0.42f, x + w, y + h), 90, -180);
        cl.arcTo(new RectF(x + w * 0.3f, y, x + w * 0.76f, y + h * 0.9f), -20, -150);
        cl.arcTo(new RectF(x, y + h * 0.38f, x + w * 0.4f, y + h), 270, -180);
        cl.close();
        c.drawPath(cl, p);
    }

    @Override public void setAlpha(int a) { p.setAlpha(a); }
    @Override public void setColorFilter(ColorFilter cf) { p.setColorFilter(cf); }
    @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
}
