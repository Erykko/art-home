package com.eric.artlauncher;

/** Build flavour. build_release.sh sets PLAY_STORE = true for the Google Play bundle. */
public final class Flavor {
    /** Google Play forbids self-updates, so the APK upload/install path is turned off there. */
    public static final boolean PLAY_STORE = false;

    private Flavor() {
    }
}
