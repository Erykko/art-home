package com.eric.artlauncher;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * tvOS-style tiles: a wide rounded banner that lifts and grows when focused, with the app's
 * name fading in underneath.
 */
public final class Tiles {

    public interface Actions {
        void onClick(Apps.Entry e);

        void onLongClick(Apps.Entry e);

        void onFocus(View tile, Apps.Entry e);
    }

    private Tiles() {
    }

    static int dp(Context c, float v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, c.getResources().getDisplayMetrics());
    }

    /**
     * Frosted tile with the app's white logo and its name underneath (the minimal style).
     * Returns a vertical container [tile, label]; the focusable view is the tile (child 0).
     */
    public static LinearLayout app(Context c, Apps.Entry e, int widthDp, Actions actions) {
        final int w = dp(c, widthDp), h = Math.round(w * 0.62f), radius = dp(c, 18);

        LinearLayout box = new LinearLayout(c);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER_HORIZONTAL);
        box.setClipChildren(false);
        box.setClipToPadding(false);

        FrameLayout tile = new FrameLayout(c);
        tile.setTag(e.key);
        tile.setId(View.generateViewId());
        final GradientDrawable bg = new GradientDrawable();
        bg.setColor(0x1FFFFFFF);
        bg.setCornerRadius(radius);
        bg.setStroke(dp(c, 1), 0x1AFFFFFF);
        tile.setBackground(bg);
        tile.setFocusable(true);
        tile.setFocusableInTouchMode(true);
        tile.setContentDescription(e.label);

        final View content;
        if (e.isApp || e.icon != null) {
            ImageView logo = new ImageView(c);
            logo.setImageDrawable(e.isApp ? Logos.mono(e) : e.icon);
            int sz = Math.round(h * 0.42f);
            tile.addView(logo, new FrameLayout.LayoutParams(sz, sz, Gravity.CENTER));
            content = logo;
        } else {
            TextView name = new TextView(c);
            name.setText(e.label);
            name.setTextColor(Color.WHITE);
            name.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
            name.setGravity(Gravity.CENTER);
            tile.addView(name, match());
            content = name;
        }
        final View sheen = sheen(c, tile);

        final TextView label = new TextView(c);
        label.setText(e.label);
        label.setTextColor(0xB3FFFFFF);
        label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        label.setSingleLine(true);
        label.setEllipsize(android.text.TextUtils.TruncateAt.END);
        label.setGravity(Gravity.CENTER);
        label.setPadding(0, dp(c, 10), 0, 0);
        if (!e.isApp && e.icon == null) label.setVisibility(View.INVISIBLE);

        tile.setOnFocusChangeListener((v, hasFocus) -> {
            bg.setColor(hasFocus ? 0x47FFFFFF : 0x1FFFFFFF);
            float s = hasFocus ? 1.08f : 1f;
            android.view.animation.Interpolator ease = new android.view.animation.DecelerateInterpolator(1.6f);
            v.animate().scaleX(s).scaleY(s).setInterpolator(ease).setDuration(220).start();
            content.animate().scaleX(hasFocus ? 1.12f : 1f).scaleY(hasFocus ? 1.12f : 1f)
                    .setInterpolator(ease).setDuration(220).start();
            label.setTextColor(hasFocus ? Color.WHITE : 0xB3FFFFFF);
            if (hasFocus) {
                playSheen(sheen, w);
                actions.onFocus(v, e);
            }
        });
        tile.setOnClickListener(v -> actions.onClick(e));
        tile.setOnLongClickListener(v -> {
            actions.onLongClick(e);
            return true;
        });
        tile.setOnKeyListener((v, keyCode, event) -> {
            if (keyCode == android.view.KeyEvent.KEYCODE_MENU && event.getAction() == android.view.KeyEvent.ACTION_UP) {
                actions.onLongClick(e);
                return true;
            }
            return false;
        });

        tile.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), radius);
            }
        });
        tile.setClipToOutline(true);
        box.addView(tile, new LinearLayout.LayoutParams(w, h));
        box.addView(label, new LinearLayout.LayoutParams(w, ViewGroup.LayoutParams.WRAP_CONTENT));
        return box;
    }

    /** A soft diagonal light band that sweeps once across a focused item (tvOS-style sheen). */
    public static View sheen(Context c, FrameLayout host) {
        View v = new View(c);
        v.setBackground(new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                new int[]{0x00FFFFFF, 0x2EFFFFFF, 0x00FFFFFF}));
        v.setRotation(18);
        v.setAlpha(0f);
        host.addView(v, new FrameLayout.LayoutParams(dp(c, 70), ViewGroup.LayoutParams.MATCH_PARENT));
        return v;
    }

    public static void playSheen(View sheen, int hostWidth) {
        sheen.animate().cancel();
        sheen.setTranslationX(-sheen.getContext().getResources().getDisplayMetrics().density * 90);
        sheen.setAlpha(1f);
        sheen.animate().translationX(hostWidth + 20).setDuration(650)
                .setInterpolator(new android.view.animation.AccelerateDecelerateInterpolator())
                .withEndAction(() -> sheen.setAlpha(0f)).start();
    }

    /** A plain tile used for "All Apps" (grid-of-dots icon). */
    public static Apps.Entry allAppsEntry(Context c) {
        Apps.Entry e = new Apps.Entry();
        e.key = "__all_apps__";
        e.label = "All Apps";
        e.isApp = false;
        e.icon = new GridDotsDrawable();
        return e;
    }

    private static FrameLayout.LayoutParams match() {
        return new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
    }

    /** 3x3 white dots, like the tvOS app-grid glyph. */
    static final class GridDotsDrawable extends android.graphics.drawable.Drawable {
        private final android.graphics.Paint p = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);

        GridDotsDrawable() {
            p.setColor(Color.WHITE);
        }

        @Override
        public void draw(android.graphics.Canvas canvas) {
            float s = Math.min(getBounds().width(), getBounds().height());
            float cx = getBounds().exactCenterX(), cy = getBounds().exactCenterY();
            float gap = s * 0.28f, r = s * 0.075f;
            for (int i = -1; i <= 1; i++) for (int j = -1; j <= 1; j++) canvas.drawCircle(cx + i * gap, cy + j * gap, r, p);
        }

        @Override
        public void setAlpha(int alpha) {
            p.setAlpha(alpha);
        }

        @Override
        public void setColorFilter(android.graphics.ColorFilter cf) {
            p.setColorFilter(cf);
        }

        @Override
        public int getOpacity() {
            return android.graphics.PixelFormat.TRANSLUCENT;
        }
    }
}
