package com.eric.artlauncher;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Collections;

/**
 * Tiny home-network HTTP server that runs only while the "Add from phone" screen is open.
 * Serves an upload page and accepts photos (PUT /photo) and an Art Home APK (PUT /apk).
 * Every request must carry the one-time token shown in the QR code, and only clients on
 * the same private network are accepted.
 */
public final class UploadServer {

    public interface Listener {
        void onPhoto(File f);

        void onApk(File f);
    }

    public static final int PORT = 8765;
    private static final long MAX_PHOTO = 40L * 1024 * 1024;
    private static final long MAX_APK = 50L * 1024 * 1024;

    private final File photoDir;
    private final File apkFile;
    private final Listener listener;
    private final String token;
    private ServerSocket server;
    private volatile boolean running;

    public UploadServer(File photoDir, File apkFile, Listener listener) {
        this.photoDir = photoDir;
        this.apkFile = apkFile;
        this.listener = listener;
        byte[] b = new byte[9];
        new SecureRandom().nextBytes(b);
        StringBuilder t = new StringBuilder();
        for (byte x : b) t.append(String.format("%02x", x));
        this.token = t.toString();
    }

    /** URL to show / encode in the QR code, including the one-time token. */
    public String url() {
        return "http://" + lanAddress() + ":" + PORT + "/?t=" + token;
    }

    public void start() throws Exception {
        server = new ServerSocket(PORT);
        running = true;
        Thread t = new Thread(() -> {
            while (running) {
                try {
                    Socket s = server.accept();
                    new Thread(() -> handle(s)).start();
                } catch (Exception e) {
                    if (!running) return;
                }
            }
        });
        t.setDaemon(true);
        t.start();
    }

    public void stop() {
        running = false;
        try {
            if (server != null) server.close();
        } catch (Exception ignored) {
        }
    }

    /** First private IPv4 address of this TV. */
    public static String lanAddress() {
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                for (InetAddress a : Collections.list(ni.getInetAddresses())) {
                    if (a instanceof Inet4Address && a.isSiteLocalAddress()) return a.getHostAddress();
                }
            }
        } catch (Exception ignored) {
        }
        return "0.0.0.0";
    }

    private void handle(Socket s) {
        try (Socket sock = s; InputStream in = sock.getInputStream();
             OutputStream out = new BufferedOutputStream(sock.getOutputStream())) {
            sock.setSoTimeout(60_000);
            // Home network only.
            InetAddress remote = sock.getInetAddress();
            if (!(remote.isSiteLocalAddress() || remote.isLoopbackAddress())) return;

            String requestLine = readLine(in);
            if (requestLine == null) return;
            long length = 0;
            String line;
            while ((line = readLine(in)) != null && !line.isEmpty()) {
                int colon = line.indexOf(':');
                if (colon > 0 && line.substring(0, colon).trim().equalsIgnoreCase("Content-Length")) {
                    length = Long.parseLong(line.substring(colon + 1).trim());
                }
            }
            String[] parts = requestLine.split(" ");
            String method = parts[0], target = parts.length > 1 ? parts[1] : "/";
            if (!token.equals(query(target, "t"))) {
                respond(out, 403, "text/plain", "Scan the QR code on the TV again.");
                return;
            }

            if (method.equals("GET") && target.startsWith("/?")) {
                respond(out, 200, "text/html; charset=utf-8", PAGE.replace("__TOKEN__", token));
            } else if (method.equals("PUT") && target.startsWith("/photo")) {
                if (length <= 0 || length > MAX_PHOTO) {
                    respond(out, 413, "text/plain", "Too large");
                    return;
                }
                File dest = new File(photoDir, System.currentTimeMillis() + "_" + safeName(query(target, "name")));
                save(in, length, dest);
                listener.onPhoto(dest);
                respond(out, 200, "text/plain", "ok");
            } else if (method.equals("PUT") && target.startsWith("/apk") && !Flavor.PLAY_STORE) {
                if (length <= 0 || length > MAX_APK) {
                    respond(out, 413, "text/plain", "Too large");
                    return;
                }
                save(in, length, apkFile);
                listener.onApk(apkFile);
                respond(out, 200, "text/plain", "Check the TV to confirm the update.");
            } else {
                respond(out, 404, "text/plain", "Not found");
            }
        } catch (Exception ignored) {
        }
    }

    private static void save(InputStream in, long length, File dest) throws Exception {
        File tmp = new File(dest.getPath() + ".part");
        try (FileOutputStream fo = new FileOutputStream(tmp)) {
            byte[] buf = new byte[65536];
            long left = length;
            while (left > 0) {
                int n = in.read(buf, 0, (int) Math.min(buf.length, left));
                if (n < 0) throw new Exception("Upload cut short");
                fo.write(buf, 0, n);
                left -= n;
            }
        }
        if (!tmp.renameTo(dest)) throw new Exception("Could not save");
    }

    private static String query(String target, String key) {
        int q = target.indexOf('?');
        if (q < 0) return "";
        for (String kv : target.substring(q + 1).split("&")) {
            int eq = kv.indexOf('=');
            if (eq > 0 && kv.substring(0, eq).equals(key)) {
                try {
                    return URLDecoder.decode(kv.substring(eq + 1), "UTF-8");
                } catch (Exception e) {
                    return "";
                }
            }
        }
        return "";
    }

    private static String safeName(String name) {
        String n = name.replaceAll("[^A-Za-z0-9._-]", "_");
        if (n.isEmpty()) n = "photo.jpg";
        return n.length() > 80 ? n.substring(n.length() - 80) : n;
    }

    private static String readLine(InputStream in) throws Exception {
        StringBuilder b = new StringBuilder();
        int c;
        while ((c = in.read()) >= 0) {
            if (c == '\n') return b.toString().replace("\r", "");
            if (b.length() > 8192) return null;
            b.append((char) c);
        }
        return b.length() == 0 ? null : b.toString();
    }

    private static void respond(OutputStream out, int code, String type, String body) throws Exception {
        byte[] data = body.getBytes(StandardCharsets.UTF_8);
        String status = code == 200 ? "OK" : code == 404 ? "Not Found" : code == 403 ? "Forbidden" : "Error";
        out.write(("HTTP/1.1 " + code + " " + status + "\r\nContent-Type: " + type
                + "\r\nContent-Length: " + data.length + "\r\nConnection: close\r\n\r\n")
                .getBytes(StandardCharsets.UTF_8));
        out.write(data);
        out.flush();
    }

    private static final String PAGE = "<!doctype html><html><head><meta charset=utf-8>"
            + "<meta name=viewport content='width=device-width,initial-scale=1'><title>Art Home</title>"
            + "<style>body{font-family:-apple-system,system-ui,sans-serif;background:#111;color:#eee;"
            + "max-width:520px;margin:auto;padding:24px}h1{font-weight:500}label{display:block;"
            + "background:#fff;color:#111;text-align:center;padding:18px;border-radius:14px;font-size:18px;"
            + "margin:16px 0}input{display:none}#log{color:#aaa;white-space:pre-line}small{color:#888}</style>"
            + "</head><body><h1>Art Home</h1><p>Send pictures to the TV. Landscape photos look best.</p>"
            + "<label>Choose photos<input id=p type=file accept='image/*' multiple></label>"
            + "<div id=log></div><hr style='border-color:#333;margin:32px 0'>"
            + (Flavor.PLAY_STORE ? "" : "<small>Update Art Home: choose a new ArtHome.apk</small>"
            + "<label style='background:#333;color:#eee'>Choose APK<input id=a type=file accept='.apk'></label>")
            + "<script>const T='__TOKEN__';const log=m=>document.getElementById('log').textContent+=m+'\\n';"
            + "document.getElementById('p').onchange=async e=>{for(const f of e.target.files){"
            + "log('Sending '+f.name+'…');const r=await fetch('/photo?t='+T+'&name='+encodeURIComponent(f.name),"
            + "{method:'PUT',body:f});log(r.ok?'✓ '+f.name:'✗ '+f.name+' ('+r.status+')');}log('Done.');};"
            + "const a=document.getElementById('a');if(a)a.onchange=async e=>{const f=e.target.files[0];if(!f)return;"
            + "log('Uploading update…');const r=await fetch('/apk?t='+T,{method:'PUT',body:f});"
            + "log(await r.text());};</script></body></html>";
}
