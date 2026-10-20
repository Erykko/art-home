package com.eric.artlauncher;

import android.accessibilityservice.AccessibilityService;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Google TV ignores the system "default home" setting, so this service watches for the
 * Google TV home window and immediately brings Art Home to the front instead.
 * It also reads the profile chooser to know which Google TV profile is active.
 */
public class HomeRedirectService extends AccessibilityService {

    private static final String GOOGLE_TV = "com.google.android.apps.tv.launcherx";
    private static final String PROFILE_MARKER = ": select to";

    /** Set briefly when the user opens Google TV on purpose from the Art Home row. */
    static volatile long allowGoogleTvUntil = 0;
    /** True after Art Home hands a search to Google TV. */
    static volatile boolean searchSession = false;

    static void allowGoogleTvSearch() {
        searchSession = true;
        allowGoogleTvUntil = SystemClock.elapsedRealtime() + 60_000;
    }
    /** Set when the user opens the profile switcher from Art Home (never auto-skip that). */
    static volatile long userOpenedChooserAt = 0;
    /** True while this service is bound; Art Home shows a warning when it isn't. */
    static volatile boolean connected = false;

    private Prefs prefs;
    private final Handler handler = new Handler(Looper.getMainLooper());

    /** Android announces Home presses with this broadcast (reason "homekey"). */
    private final android.content.BroadcastReceiver homeKeyReceiver = new android.content.BroadcastReceiver() {
        @Override
        public void onReceive(android.content.Context c, Intent intent) {
            if (!"homekey".equals(intent.getStringExtra("reason"))) return;
            // Home always means "go home": end any Google TV search session.
            searchSession = false;
            allowGoogleTvUntil = 0;
            handler.postDelayed(() -> {
                AccessibilityNodeInfo root = getRootInActiveWindow();
                if (root != null && GOOGLE_TV.contentEquals(root.getPackageName())) maybeRedirect();
            }, 600);
        }
    };

    @Override
    public void onDestroy() {
        try {
            unregisterReceiver(homeKeyReceiver);
        } catch (Exception ignored) {
        }
        super.onDestroy();
    }

    @Override
    protected void onServiceConnected() {
        prefs = new Prefs(this);
        connected = true;
        try {
            registerReceiver(homeKeyReceiver,
                    new android.content.IntentFilter(Intent.ACTION_CLOSE_SYSTEM_DIALOGS),
                    android.content.Context.RECEIVER_EXPORTED);
        } catch (Exception ignored) {
        }
        // After an update or restart Google TV may already be in front with no new window
        // event coming, so check once now.
        handler.postDelayed(() -> {
            AccessibilityNodeInfo root = getRootInActiveWindow();
            if (root != null && GOOGLE_TV.contentEquals(root.getPackageName())) maybeRedirect();
        }, 1500);
    }

    @Override
    public boolean onUnbind(android.content.Intent intent) {
        connected = false;
        return super.onUnbind(intent);
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        CharSequence pkg = event.getPackageName();
        if (pkg == null || !GOOGLE_TV.contentEquals(pkg)) return;
        if (prefs == null) prefs = new Prefs(this);

        CharSequence cls = event.getClassName();
        String className = cls == null ? "" : cls.toString();

        switch (event.getEventType()) {
            case AccessibilityEvent.TYPE_VIEW_CLICKED:
                rememberChosenProfile(event);
                return;
            case AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED:
                if (className.contains("ProfileChooser")) {
                    collectProfiles(getRootInActiveWindow());
                    maybeSkipChooser();
                    return;
                }
                if (className.contains("HomeActivity")) maybeRedirect();
                return;
            default:
        }
    }

    private boolean shouldShowArtHome() {
        long now = SystemClock.elapsedRealtime();
        if (now < allowGoogleTvUntil) {
            // Google TV search results live inside its home screen; keep allowing it while
            // the user is browsing them.
            if (searchSession) allowGoogleTvUntil = now + 60_000;
            return false;
        }
        searchSession = false;
        // Kids profiles suspend unapproved apps; launching would just show "App isn't available".
        if (getPackageManager().isPackageSuspended()) return false;
        return prefs.enabledForCurrentProfile();
    }

    private void maybeRedirect() {
        detectProfileOnHome(getRootInActiveWindow());
        if (shouldShowArtHome()) openArtHome();
    }

    private void openArtHome() {
        Intent i = new Intent(this, HomeActivity.class);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                | Intent.FLAG_ACTIVITY_CLEAR_TOP
                | Intent.FLAG_ACTIVITY_NO_ANIMATION);
        startActivity(i);
    }

    /**
     * The profile chooser can be missed (e.g. while a kids profile had Art Home suspended),
     * so also look for a known profile name on the Google TV home itself (avatar / greeting).
     */
    private void detectProfileOnHome(AccessibilityNodeInfo root) {
        if (root == null) return;
        Set<String> known = prefs.knownProfiles();
        if (known.isEmpty()) return;
        String found = findProfileLabel(root, known, 0);
        if (found != null) prefs.setCurrentProfile(found);
    }

    private String findProfileLabel(AccessibilityNodeInfo node, Set<String> known, int depth) {
        if (node == null || depth > 12) return null;
        for (CharSequence cs : new CharSequence[]{node.getContentDescription(), node.getText()}) {
            if (cs == null) continue;
            String s = cs.toString();
            for (String name : known) {
                String first = name.split(" ")[0];
                if (s.contains(name) || s.contains("Hello, " + first)) return name;
            }
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            String r = findProfileLabel(node.getChild(i), known, depth + 1);
            if (r != null) return r;
        }
        return null;
    }

    /** Profile tiles read "Name, email, : select to log in" — keep the name part. */
    private static String profileName(CharSequence desc) {
        if (desc == null) return null;
        String d = desc.toString();
        if (!d.contains(PROFILE_MARKER)) return null;
        int comma = d.indexOf(',');
        return (comma > 0 ? d.substring(0, comma) : d).trim();
    }

    private void rememberChosenProfile(AccessibilityEvent event) {
        String name = profileName(event.getContentDescription());
        if (name == null && event.getSource() != null) {
            name = profileName(event.getSource().getContentDescription());
        }
        if (name != null) {
            prefs.setCurrentProfile(name);
            prefs.setManualProfile(""); // a real choice beats the manual fallback
        }
    }

    /**
     * Optional: at startup, pick the last-used profile automatically. Never when the user
     * opened the switcher themselves from Art Home.
     */
    private void maybeSkipChooser() {
        if (!prefs.skipProfileChooser()) return;
        if (SystemClock.elapsedRealtime() - userOpenedChooserAt < 60_000) return;
        final String last = prefs.currentProfile();
        if (last.isEmpty()) return;
        // Give the chooser a moment to lay out before clicking.
        handler.postDelayed(() -> {
            AccessibilityNodeInfo target = findProfileNode(getRootInActiveWindow(), last);
            while (target != null && !target.isClickable()) target = target.getParent();
            if (target != null) target.performAction(AccessibilityNodeInfo.ACTION_CLICK);
        }, 800);
    }

    private AccessibilityNodeInfo findProfileNode(AccessibilityNodeInfo node, String name) {
        if (node == null) return null;
        if (name.equals(profileName(node.getContentDescription()))) return node;
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo r = findProfileNode(node.getChild(i), name);
            if (r != null) return r;
        }
        return null;
    }

    private void collectProfiles(AccessibilityNodeInfo root) {
        if (root == null) return;
        Set<String> names = new LinkedHashSet<>();
        String current = walk(root, names);
        if (names.isEmpty()) return;
        Set<String> known = prefs.knownProfiles();
        known.addAll(names);
        prefs.setKnownProfiles(known);
        if (current != null && prefs.currentProfile().isEmpty()) prefs.setCurrentProfile(current);
    }

    /** Collects profile names; returns the one marked as the current account, if any. */
    private String walk(AccessibilityNodeInfo node, Set<String> names) {
        if (node == null) return null;
        String current = null;
        CharSequence desc = node.getContentDescription();
        String name = profileName(desc);
        if (name != null) {
            names.add(name);
            if (desc.toString().contains("current account")) current = name;
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            String c = walk(node.getChild(i), names);
            if (c != null) current = c;
        }
        return current;
    }

    @Override
    public void onInterrupt() {
    }
}
