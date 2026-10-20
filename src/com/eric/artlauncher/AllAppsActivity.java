package com.eric.artlauncher;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Color;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * All Apps: translucent over the artwork. Sections for HDMI inputs, recently used apps and a
 * grid of every app. Long-press OK on an app to add/remove it from the top row or hide it.
 */
public class AllAppsActivity extends Activity {

    private static final int COLUMNS = 6;
    private int TILE_DP = 120;

    private Prefs prefs;
    private LinearLayout content;
    private String focusKey;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = new Prefs(this);
        float screenDp = getResources().getDisplayMetrics().widthPixels / getResources().getDisplayMetrics().density;
        TILE_DP = (int) Math.min(150, (screenDp - 112 - 16) / COLUMNS - 20);

        FrameLayout root = new FrameLayout(this);
        Style.frostedBackdrop(this, root, 0xB3);
        ScrollView scroller = new ScrollView(this);
        scroller.setClipChildren(false);
        scroller.setClipToPadding(false);
        scroller.setVerticalScrollBarEnabled(false);
        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setClipChildren(false);
        content.setClipToPadding(false);
        content.setPadding(dp(56), dp(36), dp(56), dp(64));
        scroller.addView(content);
        root.addView(scroller, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        setContentView(root);
    }

    @Override
    protected void onResume() {
        super.onResume();
        rebuild();
    }

    private int dp(float v) {
        return Tiles.dp(this, v);
    }

    private void rebuild() {
        content.removeAllViews();
        TextView title = new TextView(this);
        title.setText("All Apps");
        title.setTextColor(Color.WHITE);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 30);
        Style.setFont(title, Style.light(this));
        title.setPadding(dp(4), 0, 0, dp(8));
        content.addView(title);

        Tiles.Actions actions = new Tiles.Actions() {
            @Override
            public void onClick(Apps.Entry e) {
                Apps.launch(AllAppsActivity.this, e);
            }

            @Override
            public void onLongClick(Apps.Entry e) {
                if (e.isApp) showMenu(e);
            }

            @Override
            public void onFocus(View tile, Apps.Entry e) {
                focusKey = e.key;
            }
        };

        if (prefs.showInputs()) {
            List<Apps.Entry> inputs = Apps.inputs(this);
            if (!inputs.isEmpty()) {
                header("Inputs");
                content.addView(row(inputs, actions));
            }
        }
        if (prefs.showContinueWatching()) {
            List<Apps.Entry> recent = Apps.recent(this, prefs, 6);
            if (!recent.isEmpty()) {
                header("Recently used");
                content.addView(row(recent, actions));
            }
        }

        header("Apps");
        List<Apps.Entry> all = Apps.all(this, prefs);
        Set<String> dock = new java.util.HashSet<>(Apps.dockKeys(this, prefs, all));
        LinearLayout gridRow = null;
        for (int i = 0; i < all.size(); i++) {
            if (i % COLUMNS == 0) {
                gridRow = newRow();
                content.addView(gridRow);
            }
            LinearLayout t = Tiles.app(this, all.get(i), TILE_DP, actions);
            if (dock.contains(all.get(i).key)) t.getChildAt(0).setContentDescription(all.get(i).label + ", in top row");
            gridRow.addView(t, tileLp());
        }

        Style.font(content);
        View toFocus = focusKey == null ? null : content.findViewWithTag(focusKey);
        if (toFocus == null) {
            View first = firstFocusable(content);
            toFocus = first;
        }
        if (toFocus != null) toFocus.requestFocus();
    }

    @Override
    public void finish() {
        super.finish();
        Style.exit(this);
    }

    private View firstFocusable(ViewGroup g) {
        for (int i = 0; i < g.getChildCount(); i++) {
            View v = g.getChildAt(i);
            if (v.isFocusable()) return v;
            if (v instanceof ViewGroup) {
                View f = firstFocusable((ViewGroup) v);
                if (f != null) return f;
            }
        }
        return null;
    }

    private void header(String text) {
        TextView h = new TextView(this);
        h.setText(text);
        h.setTextColor(0xFFB0B4BA);
        h.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        h.setPadding(dp(4), dp(22), 0, dp(4));
        content.addView(h);
    }

    private LinearLayout newRow() {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setClipChildren(false);
        r.setClipToPadding(false);
        return r;
    }

    private LinearLayout.LayoutParams tileLp() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(dp(10), dp(10), dp(10), dp(4));
        return lp;
    }

    private View row(List<Apps.Entry> entries, Tiles.Actions actions) {
        HorizontalScrollView hs = new HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        hs.setClipChildren(false);
        hs.setClipToPadding(false);
        LinearLayout r = newRow();
        for (Apps.Entry e : entries) r.addView(Tiles.app(this, e, TILE_DP, actions), tileLp());
        hs.addView(r);
        return hs;
    }

    private void showMenu(final Apps.Entry e) {
        final List<String> dock = new ArrayList<>(Apps.dockKeys(this, prefs, Apps.all(this, prefs)));
        final boolean inDock = dock.contains(e.key);
        String[] items = {inDock ? "Remove from top row" : "Add to top row", "Hide app"};
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle(e.label)
                .setItems(items, (d, which) -> {
                    if (which == 0) {
                        if (inDock) dock.remove(e.key);
                        else if (dock.size() < Apps.DOCK_MAX) dock.add(e.key);
                        else {
                            android.widget.Toast.makeText(this, "The top row holds " + Apps.DOCK_MAX
                                    + " apps. Remove one first.", android.widget.Toast.LENGTH_LONG).show();
                            return;
                        }
                        prefs.setAppOrder(dock);
                    } else {
                        Set<String> hidden = prefs.hiddenApps();
                        hidden.add(e.key);
                        prefs.setHiddenApps(hidden);
                        dock.remove(e.key);
                        prefs.setAppOrder(dock);
                    }
                    rebuild();
                })
                .show();
    }
}
