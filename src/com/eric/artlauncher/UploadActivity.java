package com.eric.artlauncher;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Matrix;
import android.media.ExifInterface;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * "Add from phone": shows a QR code for the upload page and runs the upload server only
 * while this screen is open. Received photos go to the "Phone uploads" source; a received
 * APK is installed through the system installer (the TV asks to confirm).
 */
public class UploadActivity extends Activity {

    private UploadServer server;
    private TextView status;
    private int received = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.HORIZONTAL);
        root.setGravity(Gravity.CENTER);
        root.setBackgroundColor(0xF0101114);
        Style.font(root);
        root.setPadding(dp(64), dp(48), dp(64), dp(48));

        ImageView qr = new ImageView(this);
        root.addView(qr, new LinearLayout.LayoutParams(dp(300), dp(300)));

        LinearLayout text = new LinearLayout(this);
        text.setOrientation(LinearLayout.VERTICAL);
        text.setPadding(dp(48), 0, 0, 0);
        TextView title = label("Add from your phone", 30, Color.WHITE);
        TextView steps = label("1. Connect your phone to the same Wi-Fi as the TV.\n"
                + "2. Scan this code with the phone camera.\n"
                + "3. Choose photos — they appear on Art Home as they arrive."
                + (Flavor.PLAY_STORE ? "" : "\n\nYou can also send a new ArtHome.apk from the same page to update the app."),
                16, 0xFFCFD3D8);
        steps.setLineSpacing(0, 1.3f);
        steps.setPadding(0, dp(16), 0, dp(16));
        TextView url = label("", 15, 0xFF9AA0A6);
        status = label("Waiting for your phone…", 16, 0xFF8AB4F8);
        status.setPadding(0, dp(16), 0, 0);
        TextView hint = label("Press Back when you're done. The page stops working when you leave this screen.",
                13, 0xFF80868B);
        hint.setPadding(0, dp(24), 0, 0);
        text.addView(title);
        text.addView(steps);
        text.addView(url);
        text.addView(status);
        text.addView(hint);
        root.addView(text, new LinearLayout.LayoutParams(dp(560), LinearLayout.LayoutParams.WRAP_CONTENT));
        setContentView(root);

        server = new UploadServer(ArtSources.uploadsDir(this), new File(getCacheDir(), "update.apk"),
                new UploadServer.Listener() {
                    @Override
                    public void onPhoto(File f) {
                        shrink(UploadActivity.this, f);
                        runOnUiThread(() -> {
                            received++;
                            status.setText("✓ " + received + (received == 1 ? " photo" : " photos")
                                    + " received. They'll show up on Art Home.");
                        });
                    }

                    @Override
                    public void onApk(File f) {
                        runOnUiThread(() -> status.setText("Update received. Confirm the install on the TV."));
                        install(f);
                    }
                });
        try {
            server.start();
            String link = server.url();
            qr.setImageBitmap(QrCode.bitmap(link, 8));
            url.setText(link);
        } catch (Exception e) {
            status.setText("Couldn't start the upload page: " + e.getMessage());
        }

        // Make sure uploads are shown.
        Prefs prefs = new Prefs(this);
        Set<String> sources = prefs.sources();
        if (sources.add(Prefs.SOURCE_UPLOADS)) prefs.setSources(sources);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (server != null) server.stop();
    }

    @Override
    protected void onPause() {
        super.onPause();
        // Don't leave the page reachable once the screen is gone (keep it while the installer shows).
        if (isFinishing() && server != null) server.stop();
    }

    private int dp(float v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics());
    }

    private TextView label(String s, float sp, int color) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextColor(color);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        return t;
    }

    /**
     * Phone photos are huge; keep a JPEG at most 2560 px on the long side (sharp on the TV,
     * small on disk), scaled smoothly and turned upright using the photo's EXIF orientation.
     */
    private static void shrink(android.content.Context c, File f) {
        try {
            // EXIF is lost when re-encoding, so read the details first.
            PhotoDetails details = PhotoDetails.read(f.getPath());
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(f.getPath(), o);
            if (o.outWidth <= 0) {
                f.delete(); // not an image
                return;
            }
            final int maxLong = 2560;
            int sample = 1;
            while (Math.max(o.outWidth, o.outHeight) / (sample * 2) >= maxLong) sample *= 2;
            BitmapFactory.Options d = new BitmapFactory.Options();
            d.inSampleSize = sample;
            Bitmap bmp = BitmapFactory.decodeFile(f.getPath(), d);
            if (bmp == null) return;

            Matrix m = new Matrix();
            float scale = Math.min(1f, (float) maxLong / Math.max(bmp.getWidth(), bmp.getHeight()));
            m.postScale(scale, scale);
            switch (new ExifInterface(f.getPath()).getAttributeInt(
                    ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                case ExifInterface.ORIENTATION_ROTATE_90: m.postRotate(90); break;
                case ExifInterface.ORIENTATION_ROTATE_180: m.postRotate(180); break;
                case ExifInterface.ORIENTATION_ROTATE_270: m.postRotate(270); break;
                case ExifInterface.ORIENTATION_FLIP_HORIZONTAL: m.postScale(-1, 1); break;
                default:
            }
            Bitmap out = Bitmap.createBitmap(bmp, 0, 0, bmp.getWidth(), bmp.getHeight(), m, true);
            if (out != bmp) bmp.recycle();

            File dest = new File(f.getParent(), f.getName().replaceAll("\\.[^.]*$", "") + ".jpg");
            File tmp = new File(dest.getPath() + ".tmp");
            try (OutputStream os = new FileOutputStream(tmp)) {
                out.compress(Bitmap.CompressFormat.JPEG, 90, os);
            }
            out.recycle();
            if (!dest.equals(f)) f.delete();
            tmp.renameTo(dest);

            // Uploads are named "<time>_<original name>". Re-sending a photo replaces an older,
            // low-quality copy (one saved before details were recorded). Copies that have details
            // are never removed, since some phones give every photo the same generic name.
            String base = dest.getName().replaceAll("\\.jpg$", "");
            String original = base.contains("_") ? base.substring(base.indexOf('_') + 1) : base;
            java.util.Set<String> withDetails = new java.util.HashSet<>();
            File metaFile = new File(dest.getParentFile(), "meta.txt");
            if (metaFile.exists()) {
                for (String line : new String(java.nio.file.Files.readAllBytes(metaFile.toPath()), "UTF-8").split("\n")) {
                    int bar = line.indexOf('|');
                    if (bar > 0) withDetails.add(line.substring(0, bar));
                }
            }
            File[] siblings = dest.getParentFile().listFiles();
            if (siblings != null) {
                for (File other : siblings) {
                    String n = other.getName();
                    if (other.equals(dest) || !n.endsWith("_" + original + ".jpg")) continue;
                    if (!withDetails.contains(n.replaceAll("\\.jpg$", ""))) other.delete();
                }
            }

            details.resolvePlace(c);
            String title = !details.place.isEmpty() ? details.place
                    : !details.date.isEmpty() ? details.date : original;
            String subtitle = !details.place.isEmpty() ? details.date : "";
            List<String> extra = new ArrayList<>();
            if (!details.camera.isEmpty()) extra.add(details.camera);
            extra.add(original);
            ArtMeta.append(dest.getParentFile(), base, title, subtitle, String.join(" · ", extra));
        } catch (Exception ignored) {
        }
    }

    private void install(File apk) {
        try {
            PackageInstaller pi = getPackageManager().getPackageInstaller();
            PackageInstaller.SessionParams params =
                    new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
            params.setAppPackageName(getPackageName());
            int id = pi.createSession(params);
            try (PackageInstaller.Session session = pi.openSession(id)) {
                try (InputStream in = new FileInputStream(apk);
                     OutputStream out = session.openWrite("ArtHome.apk", 0, apk.length())) {
                    byte[] buf = new byte[65536];
                    int n;
                    while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                    session.fsync(out);
                }
                Intent done = new Intent(this, InstallReceiver.class);
                PendingIntent pending = PendingIntent.getBroadcast(this, id, done,
                        PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_MUTABLE);
                session.commit(pending.getIntentSender());
            }
        } catch (Exception e) {
            runOnUiThread(() -> status.setText("Update failed: " + e.getMessage()));
        }
    }
}
