package com.eric.artlauncher;

import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.view.View;

/**
 * Thin outline used as a view's foreground: painted with the moving gradient (brightened)
 * while the carousel shows, or a plain light line otherwise. Same look as the dock's rim.
 */
public class RimDrawable extends Drawable {

    private final View host;
    private final GradientField field;
    private float radius;
    private final float stroke;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Matrix m = new Matrix();
    private final int[] loc = new int[2];
    private final RectF r = new RectF();
    private final ColorMatrixColorFilter bright;
    public static volatile boolean gradientOn = false;

    public RimDrawable(View host, GradientField field, float radius, float stroke) {
        this.host = host;
        this.field = field;
        this.radius = radius;
        this.stroke = stroke;
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(stroke);
        paint.setFilterBitmap(true);
        ColorMatrix cm = new ColorMatrix();
        cm.setSaturation(1.5f);
        cm.postConcat(new ColorMatrix(new float[]{
                2.4f, 0, 0, 0, 95, 0, 2.4f, 0, 0, 95, 0, 0, 2.4f, 0, 95, 0, 0, 0, 1, 0}));
        bright = new ColorMatrixColorFilter(cm);
        if (field != null) field.addListener(host);
    }

    @Override
    public void draw(Canvas c) {
        r.set(getBounds());
        r.inset(stroke / 2f, stroke / 2f);
        Bitmap b = field == null ? null : field.frame();
        if (gradientOn && b != null) {
            host.getLocationOnScreen(loc);
            float sw = host.getResources().getDisplayMetrics().widthPixels;
            float sh = host.getResources().getDisplayMetrics().heightPixels;
            // The host may be scaled (grow animation); map screen space into its local space.
            float sx = host.getScaleX(), sy = host.getScaleY();
            m.setScale(sw / b.getWidth(), sh / b.getHeight());
            m.postTranslate(-loc[0], -loc[1]);
            m.postScale(1f / sx, 1f / sy);
            BitmapShader shader = new BitmapShader(b, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
            shader.setLocalMatrix(m);
            paint.setShader(shader);
            paint.setColorFilter(bright);
        } else {
            paint.setShader(null);
            paint.setColorFilter(null);
            paint.setColor(0x59FFFFFF);
        }
        c.drawRoundRect(r, radius, radius, paint);
    }

    public void setRadius(float r) {
        radius = r;
        invalidateSelf();
    }

    @Override public void setAlpha(int a) { paint.setAlpha(a); }
    @Override public void setColorFilter(ColorFilter cf) { }
    @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
}
