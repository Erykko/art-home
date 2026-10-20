package com.eric.artlauncher;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.view.View;

/**
 * Glowing outline around the dock, painted with the moving gradient's colours (brightened):
 * a crisp rim plus a soft glow just outside it.
 */
public class GlowRim extends View {

    private final GradientField field;
    private final float radius, glow, stroke;
    private final Paint rim = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint halo = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Matrix m = new Matrix();
    private final int[] loc = new int[2];
    private final RectF r = new RectF();

    public GlowRim(Context c, GradientField field, float radius, float glow, float stroke) {
        super(c);
        this.field = field;
        this.radius = radius;
        this.glow = glow;
        this.stroke = stroke;
        field.addListener(this);
        ColorMatrix cm = new ColorMatrix();
        cm.setSaturation(1.5f);
        ColorMatrix bright = new ColorMatrix(new float[]{
                2.4f, 0, 0, 0, 95,
                0, 2.4f, 0, 0, 95,
                0, 0, 2.4f, 0, 95,
                0, 0, 0, 1, 0});
        cm.postConcat(bright);
        ColorMatrixColorFilter cf = new ColorMatrixColorFilter(cm);
        for (Paint p : new Paint[]{rim, halo}) {
            p.setStyle(Paint.Style.STROKE);
            p.setColorFilter(cf);
            p.setFilterBitmap(true);
        }
        rim.setStrokeWidth(stroke);
        halo.setStrokeWidth(glow * 0.9f);
        halo.setMaskFilter(new BlurMaskFilter(glow * 0.8f, BlurMaskFilter.Blur.NORMAL));
        halo.setAlpha(170);
    }

    @Override
    protected void onDraw(Canvas c) {
        Bitmap b = field.frame();
        if (b == null) return;
        // Map the tiny full-screen gradient onto this view's position on screen.
        getLocationOnScreen(loc);
        float sw = getResources().getDisplayMetrics().widthPixels, sh = getResources().getDisplayMetrics().heightPixels;
        m.setScale(sw / b.getWidth(), sh / b.getHeight());
        m.postTranslate(-loc[0], -loc[1]);
        BitmapShader shader = new BitmapShader(b, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
        shader.setLocalMatrix(m);
        rim.setShader(shader);
        halo.setShader(shader);
        r.set(glow, glow, getWidth() - glow, getHeight() - glow);
        // Soft outer glow removed by request; only the crisp coloured rim is drawn.
        r.inset(stroke / 2f, stroke / 2f);
        c.drawRoundRect(r, radius, radius, rim);
    }
}
