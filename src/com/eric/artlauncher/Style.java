package com.eric.artlauncher;

import android.app.Activity;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.RenderEffect;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

import java.io.File;

/** Shared look: Manrope font, colours taken from the artwork, frosted backdrops, transitions. */
public final class Style {

    private static Typeface regular, medium, light;
    /** The artwork currently on the home screen, so other screens can use it as a backdrop. */
    public static volatile File currentArt;
    /** Muted main colour of the current artwork. */
    public static volatile int artColor = 0xFF3A3A3A;

    private Style() {
    }

    private static Typeface weight(Context c, int w) {
        try {
            return new Typeface.Builder(c.getAssets(), "fonts/Manrope.ttf")
                    .setFontVariationSettings("'wght' " + w).build();
        } catch (Exception e) {
            return Typeface.DEFAULT;
        }
    }

    public static Typeface regular(Context c) { if (regular == null) regular = weight(c, 500); return regular; }
    public static Typeface medium(Context c) { if (medium == null) medium = weight(c, 600); return medium; }
    public static Typeface light(Context c) { if (light == null) light = weight(c, 300); return light; }

    /** Applies Manrope to every TextView under v (keeps views that already set a typeface tag). */
    public static void font(View v) {
        if (v instanceof TextView) {
            TextView t = (TextView) v;
            if (!"keep-font".equals(t.getTag(R.id.font_tag))) {
                boolean bold = t.getTypeface() != null && t.getTypeface().isBold();
                t.setTypeface(bold ? medium(v.getContext()) : regular(v.getContext()));
            }
        }
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) font(g.getChildAt(i));
        }
    }

    public static void setFont(TextView t, Typeface tf) {
        t.setTypeface(tf);
        t.setTag(R.id.font_tag, "keep-font");
    }

    /** Average colour of a tiny copy of the image, made calmer and darker for glass tints. */
    public static int muted(Bitmap b) {
        if (b == null) return 0xFF3A3A3A;
        Bitmap s = Bitmap.createScaledBitmap(b, 8, 8, true);
        long r = 0, g = 0, bl = 0;
        for (int y = 0; y < 8; y++) for (int x = 0; x < 8; x++) {
            int p = s.getPixel(x, y);
            r += Color.red(p);
            g += Color.green(p);
            bl += Color.blue(p);
        }
        if (s != b) s.recycle();
        float[] hsv = new float[3];
        Color.RGBToHSV((int) (r / 64), (int) (g / 64), (int) (bl / 64), hsv);
        hsv[1] = Math.min(hsv[1], 0.45f);
        hsv[2] = Math.min(Math.max(hsv[2], 0.18f), 0.38f);
        return Color.HSVToColor(hsv);
    }

    public static int withAlpha(int color, int alpha) {
        return (color & 0x00FFFFFF) | (alpha << 24);
    }

    /**
     * Adds a blurred, darkened copy of the current artwork behind a screen (All Apps, Search,
     * Settings) so they look like frosted glass over the home screen.
     */
    public static void frostedBackdrop(Activity a, FrameLayout root, int darken) {
        ImageView bg = new ImageView(a);
        bg.setScaleType(ImageView.ScaleType.CENTER_CROP);
        bg.setRenderEffect(RenderEffect.createBlurEffect(80, 80, Shader.TileMode.CLAMP));
        root.addView(bg, 0, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        View tint = new View(a);
        tint.setBackgroundColor(withAlpha(blend(artColor, 0xFF000000, 0.55f), darken));
        root.addView(tint, 1, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        root.setBackgroundColor(0xFF101114);
        final File f = currentArt;
        if (f == null) return;
        new Thread(() -> {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inSampleSize = 4;
            final Bitmap b = BitmapFactory.decodeFile(f.getPath(), o);
            if (b != null) a.runOnUiThread(() -> bg.setImageBitmap(b));
        }).start();
    }

    public static int blend(int a, int b, float t) {
        return Color.rgb(
                Math.round(Color.red(a) * (1 - t) + Color.red(b) * t),
                Math.round(Color.green(a) * (1 - t) + Color.green(b) * t),
                Math.round(Color.blue(a) * (1 - t) + Color.blue(b) * t));
    }

    /** Fade + slight zoom when opening a screen. */
    public static void enter(Activity a) {
        a.overridePendingTransition(R.anim.screen_in, R.anim.screen_hold);
    }

    public static void exit(Activity a) {
        a.overridePendingTransition(R.anim.screen_hold, R.anim.screen_out);
    }
}
