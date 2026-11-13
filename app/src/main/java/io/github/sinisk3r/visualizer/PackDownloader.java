package io.github.sinisk3r.visualizer;

import android.content.Context;
import android.util.Log;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Downloads the full "Cream of the Crop" pack (9,795 presets, ~33 MB zip, ~156 MB unpacked) from GitHub, pinned to
 * one commit, and unpacks only the .milk files into the user preset folder. Runs on its own thread.
 */
final class PackDownloader {
    private static final String TAG = "Visualizer";
    static final String COMMIT = "0180df21f5e0bd39b9060cc5de420ed2f1f9e509";
    private static final String URL_ZIP =
            "https://github.com/projectM-visualizer/presets-cream-of-the-crop/archive/" + COMMIT + ".zip";
    private static final long ZIP_BYTES = 32_781_927L;  // for the progress bar (GitHub sends no length)
    private static final String MARKER = ".cream-of-the-crop";

    interface Progress {
        /** percent 0..100, or -1 when finished; error is null on success. Called on the download thread. */
        void onProgress(int percent, String error);
    }

    private static volatile Thread running;

    private PackDownloader() {}

    static File userPresetDir(Context ctx) {
        File ext = ctx.getExternalFilesDir(null);
        if (ext == null) return null;
        File dir = new File(ext, "presets");
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        return dir;
    }

    static boolean isInstalled(Context ctx) {
        File dir = userPresetDir(ctx);
        return dir != null && new File(dir, MARKER).isFile();
    }

    static boolean isRunning() {
        return running != null;
    }

    static synchronized void start(Context ctx, Progress progress) {
        if (running != null) return;
        File dir = userPresetDir(ctx);
        if (dir == null) {
            progress.onProgress(-1, "No storage for presets.");
            return;
        }
        running = new Thread(() -> {
            String error = null;
            try {
                download(dir, progress);
            } catch (IOException e) {
                Log.e(TAG, "pack download failed", e);
                error = "Download failed: " + e.getMessage();
            } finally {
                running = null;
            }
            progress.onProgress(-1, error);
        }, "pack-download");
        running.start();
    }

    private static void download(File dir, Progress progress) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(URL_ZIP).openConnection();
        c.setConnectTimeout(15_000);
        c.setReadTimeout(30_000);
        c.setInstanceFollowRedirects(true);
        int code = c.getResponseCode();
        if (code != 200) throw new IOException("HTTP " + code);
        int lastPct = -1;
        int count = 0;
        try (CountingStream counted = new CountingStream(new BufferedInputStream(c.getInputStream(), 65536));
             ZipInputStream zip = new ZipInputStream(counted)) {
            byte[] buf = new byte[32768];
            ZipEntry e;
            while ((e = zip.getNextEntry()) != null) {
                String name = e.getName();
                // Drop the "presets-cream-of-the-crop-<commit>/" folder GitHub adds.
                int slash = name.indexOf('/');
                String rel = slash < 0 ? "" : name.substring(slash + 1);
                if (e.isDirectory() || !rel.toLowerCase(Locale.ROOT).endsWith(".milk")
                        || rel.startsWith("!") || rel.contains("..")) continue;
                File out = new File(dir, rel);
                //noinspection ResultOfMethodCallIgnored
                out.getParentFile().mkdirs();
                try (OutputStream o = new FileOutputStream(out)) {
                    int n;
                    while ((n = zip.read(buf)) > 0) o.write(buf, 0, n);
                }
                count++;
                int pct = (int) Math.min(99, counted.read * 100 / ZIP_BYTES);
                if (pct != lastPct) {
                    lastPct = pct;
                    progress.onProgress(pct, null);
                }
            }
        } finally {
            c.disconnect();
        }
        if (count < 9000) throw new IOException("only " + count + " presets in the download");
        //noinspection ResultOfMethodCallIgnored
        new File(dir, MARKER).createNewFile();
        Log.i(TAG, "full pack installed: " + count + " presets");
    }

    private static final class CountingStream extends java.io.FilterInputStream {
        long read;

        CountingStream(InputStream in) {
            super(in);
        }

        @Override
        public int read() throws IOException {
            int b = super.read();
            if (b >= 0) read++;
            return b;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            int n = super.read(b, off, len);
            if (n > 0) read += n;
            return n;
        }
    }
}
