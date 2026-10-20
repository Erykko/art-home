# Publishing to Google Play

The workflow in `.github/workflows/android.yml` builds the app on every push. Pushing a version
tag (`v6.23`, …) also:

1. builds the signed **sideload APK** and **Play bundle** (`.aab`) with `build_release.sh`,
2. attaches both to the GitHub release,
3. uploads the bundle to Google Play (internal testing track by default).

The Play build is the same app with the phone self-update removed, which Google Play doesn't
allow. Package name: `app.arthome`.

## One-time setup

### 1. GitHub secrets

In the repository: **Settings → Secrets and variables → Actions → New repository secret**

| Secret | Value |
|---|---|
| `RELEASE_KEYSTORE_BASE64` | `base64 -i release.jks` (the release key; never commit the file) |
| `RELEASE_KEY_PASS` | the key's password |
| `PLAY_SERVICE_ACCOUNT_JSON` | the Google Play service-account JSON (step 3) |

Optional variable `PLAY_TRACK`: `internal` (default), `alpha`, `beta` or `production`.

### 2. Create the app in Play Console

1. [Google Play Console](https://play.google.com/console) → **Create app** (needs a developer account).
2. Turn on **Play App Signing** and choose to upload your own key — or let Google create the
   app-signing key and use this release key as the **upload key**.
3. The Play API can't create a new app, so the **first bundle must be uploaded by hand**:
   build it locally (`./build_release.sh`) and upload `release/ArtHome-<version>.aab` under
   **Testing → Internal testing**. After that, the workflow takes over.
4. Under **Advanced settings → Form factors**, add **Android TV**, and submit the TV screenshots
   and banner for review.

### 3. Service account for uploads

1. Google Cloud Console → enable the **Google Play Android Developer API**.
2. Create a service account → **Keys → Add key → JSON**; paste the file's contents into the
   `PLAY_SERVICE_ACCOUNT_JSON` secret.
3. Play Console → **Users and permissions → Invite new user** → the service account's email,
   with permission to release to testing tracks (and production if wanted).

## Releasing

1. Bump `android:versionCode` (must always increase) and `android:versionName` in
   `AndroidManifest.xml`, commit, and push.
2. Tag and push: `git tag v6.23 && git push origin v6.23`.

To push the current `main` to a Play track without tagging: **Actions → Android → Run workflow**.

## Things Google Play reviews closely for this app

Play reviews these permissions; be ready to explain them in the console declarations:

- **Accessibility service** (`HomeRedirectService`): makes the Home button open Art Home on
  Google TV, which ignores the default-launcher setting. Play requires an
  [accessibility declaration](https://support.google.com/googleplay/android-developer/answer/10964491)
  and a prominent in-app explanation; apps that aren't accessibility tools can be rejected.
  Without the service, Art Home works only as a regular app launched from the apps row.
- **`QUERY_ALL_PACKAGES`**: needed to list every installed app (it's a launcher); requires a
  declaration ("launcher / device home" is an accepted use).
- **Notification access**: only used to read the active media session for the Now playing card.
- **Usage access** (`PACKAGE_USAGE_STATS`): for "Recently used"; optional.

Android TV apps also need a TV banner (included) and must work fully with a D-pad.
