package com.eric.artlauncher;

import android.app.Activity;
import android.app.SearchManager;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Search: filters installed apps as you type, and hands the query to Google TV search
 * (movies and shows across all apps). The mic button starts Google voice search.
 */
public class SearchActivity extends Activity {

    private static final int COLUMNS = 6;

    private Prefs prefs;
    private EditText input;
    private TextView googleRow;
    private LinearLayout results;
    private List<Apps.Entry> allApps;
    private int tileDp;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = new Prefs(this);
        allApps = Apps.all(this, prefs);
        float screenDp = getResources().getDisplayMetrics().widthPixels / getResources().getDisplayMetrics().density;
        tileDp = (int) Math.min(150, (screenDp - 112 - 16) / COLUMNS - 20);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);

        root.setPadding(dp(56), dp(36), dp(56), dp(24));
        root.setClipChildren(false);

        // Search bar: field + mic.
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        input = new EditText(this);
        input.setHint("Search apps, movies and shows");
        input.setHintTextColor(0x88FFFFFF);
        input.setTextColor(Color.WHITE);
        input.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        input.setSingleLine(true);
        input.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        input.setPadding(dp(20), dp(12), dp(20), dp(12));
        final GradientDrawable fieldBg = new GradientDrawable();
        fieldBg.setCornerRadius(dp(24));
        fieldBg.setColor(0x26FFFFFF);
        input.setBackground(fieldBg);
        input.setOnFocusChangeListener((v, f) -> fieldBg.setStroke(f ? dp(2) : 0, Color.WHITE));
        input.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void afterTextChanged(Editable s) { update(); }
        });
        input.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEARCH
                    || (event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER)) {
                searchGoogleTv();
                return true;
            }
            return false;
        });
        bar.addView(input, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        bar.addView(micButton());
        root.addView(bar);

        googleRow = pill("", this::searchGoogleTv);
        LinearLayout.LayoutParams gLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        gLp.setMargins(0, dp(18), 0, dp(4));
        root.addView(googleRow, gLp);

        results = new LinearLayout(this);
        results.setOrientation(LinearLayout.VERTICAL);
        results.setClipChildren(false);
        results.setClipToPadding(false);
        android.widget.ScrollView sv = new android.widget.ScrollView(this);
        sv.setClipChildren(false);
        sv.setClipToPadding(false);
        sv.setVerticalScrollBarEnabled(false);
        sv.addView(results);
        root.addView(sv, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        FrameLayout frame = new FrameLayout(this);
        Style.frostedBackdrop(this, frame, 0xC0);
        frame.addView(root, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        setContentView(frame);
        update();
        input.requestFocus();
    }

    private int dp(float v) {
        return Tiles.dp(this, v);
    }

    private View micButton() {
        FrameLayout b = new FrameLayout(this);
        final GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.OVAL);
        bg.setColor(0x26FFFFFF);
        b.setBackground(bg);
        b.setFocusable(true);
        b.setContentDescription("Voice search");
        final GlyphDrawable icon = new GlyphDrawable(GlyphDrawable.MIC, Color.WHITE);
        ImageView iv = new ImageView(this);
        iv.setImageDrawable(icon);
        b.addView(iv, new FrameLayout.LayoutParams(dp(24), dp(24), Gravity.CENTER));
        b.setOnFocusChangeListener((v, f) -> {
            bg.setColor(f ? Color.WHITE : 0x26FFFFFF);
            icon.setColor(f ? 0xFF111111 : Color.WHITE);
        });
        b.setOnClickListener(v -> {
            try {
                HomeRedirectService.allowGoogleTvSearch();
                startActivity(new Intent(Intent.ACTION_ASSIST).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            } catch (Exception ignored) {
            }
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(50), dp(50));
        lp.leftMargin = dp(14);
        b.setLayoutParams(lp);
        return b;
    }

    private TextView pill(String text, Runnable onClick) {
        final TextView t = new TextView(this);
        t.setText(text);
        t.setTextColor(Color.WHITE);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        t.setPadding(dp(18), dp(10), dp(18), dp(10));
        final GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(20));
        bg.setColor(0x26FFFFFF);
        t.setBackground(bg);
        t.setFocusable(true);
        t.setOnFocusChangeListener((v, f) -> {
            bg.setColor(f ? Color.WHITE : 0x26FFFFFF);
            t.setTextColor(f ? 0xFF111111 : Color.WHITE);
        });
        t.setOnClickListener(v -> onClick.run());
        return t;
    }

    private void update() {
        String q = input.getText().toString().trim();
        googleRow.setText(q.isEmpty() ? "🔍  Open Google TV search" : "🔍  Search Google TV for “" + q + "”");

        results.removeAllViews();
        List<Apps.Entry> matches = new ArrayList<>();
        String ql = q.toLowerCase(Locale.ROOT);
        for (Apps.Entry e : allApps) {
            if (ql.isEmpty() || e.label.toLowerCase(Locale.ROOT).contains(ql)) matches.add(e);
        }
        TextView header = new TextView(this);
        header.setText(matches.isEmpty() ? "No apps match" : q.isEmpty() ? "Apps" : "Apps matching “" + q + "”");
        header.setTextColor(0xFFB0B4BA);
        header.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        header.setPadding(dp(4), dp(18), 0, dp(4));
        results.addView(header);

        Tiles.Actions actions = new Tiles.Actions() {
            @Override public void onClick(Apps.Entry e) { Apps.launch(SearchActivity.this, e); }
            @Override public void onLongClick(Apps.Entry e) { }
            @Override public void onFocus(View tile, Apps.Entry e) { }
        };
        LinearLayout row = null;
        for (int i = 0; i < matches.size(); i++) {
            if (i % COLUMNS == 0) {
                row = new LinearLayout(this);
                row.setClipChildren(false);
                row.setClipToPadding(false);
                results.addView(row);
            }
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.setMargins(dp(10), dp(10), dp(10), dp(4));
            row.addView(Tiles.app(this, matches.get(i), tileDp, actions), lp);
        }
        Style.font(results);
        Style.font(googleRow);
        input.setTypeface(Style.regular(this));
    }

    @Override
    public void finish() {
        super.finish();
        Style.exit(this);
    }

    private void searchGoogleTv() {
        String q = input.getText().toString().trim();
        HomeRedirectService.allowGoogleTvSearch();
        Intent i = new Intent("android.search.action.GLOBAL_SEARCH").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (!q.isEmpty()) i.putExtra(SearchManager.QUERY, q);
        try {
            startActivity(i);
        } catch (Exception ignored) {
        }
    }
}
