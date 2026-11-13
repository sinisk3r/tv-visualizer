package io.github.sinisk3r.visualizer;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.AssetManager;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.TreeMap;

/**
 * Finds presets. Two sources, merged by category (top-level folder name):
 * 1. The pack bundled in the APK (assets/presets, assets/textures), copied to internal storage on first run
 *    because projectM reads plain files.
 * 2. An optional user folder, Android/data/<package>/files/presets, for anyone who wants the full pack
 *    (push it with adb or a file manager). Its textures go in .../files/textures.
 */
final class PresetLibrary {
    static final String ALL = "All";

    final File textureDir;
    /** Category name to preset files; "All" first, then folders in name order. */
    final TreeMap<String, List<File>> byCategory = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
    final List<String> categories = new ArrayList<>();

    PresetLibrary(Context ctx) throws IOException {
        File root = new File(ctx.getFilesDir(), "bundle");
        unpackIfNeeded(ctx, root);
        textureDir = new File(root, "textures");

        List<File> all = new ArrayList<>();
        scan(new File(root, "presets"), all);
        File ext = ctx.getExternalFilesDir(null);
        if (ext != null) {
            File user = new File(ext, "presets");
            if (!user.isDirectory()) //noinspection ResultOfMethodCallIgnored
                user.mkdirs();
            scan(user, all);
        }
        categories.add(ALL);
        categories.addAll(byCategory.keySet());
        byCategory.put(ALL, all);
    }

    /** User textures, when the user added a folder of them; else the bundled ones. */
    String textureSearchPath(Context ctx) {
        File ext = ctx.getExternalFilesDir(null);
        File user = ext == null ? null : new File(ext, "textures");
        return user != null && user.isDirectory() && hasFiles(user) ? user.getPath() : textureDir.getPath();
    }

    private static boolean hasFiles(File dir) {
        String[] names = dir.list();
        return names != null && names.length > 0;
    }

    private void scan(File dir, List<File> all) {
        File[] tops = dir.listFiles();
        if (tops == null) return;
        for (File top : tops) {
            if (top.isDirectory()) {
                // "! Transition" holds blend helpers, not presets to show.
                if (top.getName().startsWith("!")) continue;
                List<File> list = new ArrayList<>();
                collect(top, list);
                if (list.isEmpty()) continue;
                byCategory.computeIfAbsent(top.getName(), k -> new ArrayList<>()).addAll(list);
                all.addAll(list);
            } else if (isPreset(top)) {
                byCategory.computeIfAbsent("Other", k -> new ArrayList<>()).add(top);
                all.add(top);
            }
        }
    }

    private static void collect(File dir, List<File> out) {
        File[] files = dir.listFiles();
        if (files == null) return;
        for (File f : files) {
            if (f.isDirectory()) collect(f, out);
            else if (isPreset(f)) out.add(f);
        }
    }

    private static boolean isPreset(File f) {
        return f.getName().toLowerCase(Locale.ROOT).endsWith(".milk");
    }

    /** Copies the bundled pack once per app version, so an update brings a new pack. */
    private static void unpackIfNeeded(Context ctx, File root) throws IOException {
        SharedPreferences prefs = ctx.getSharedPreferences("bundle", Context.MODE_PRIVATE);
        long version;
        try {
            version = ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0).getLongVersionCode();
        } catch (Exception e) {
            version = -1;
        }
        if (prefs.getLong("version", 0) == version && new File(root, "presets").isDirectory()) return;
        deleteTree(root);
        AssetManager am = ctx.getAssets();
        copyTree(am, "presets", new File(root, "presets"));
        copyTree(am, "textures", new File(root, "textures"));
        prefs.edit().putLong("version", version).apply();
    }

    private static void copyTree(AssetManager am, String path, File dest) throws IOException {
        String[] children = am.list(path);
        if (children == null || children.length == 0) {
            // A file (asset folders always list their children).
            //noinspection ResultOfMethodCallIgnored
            dest.getParentFile().mkdirs();
            try (InputStream in = am.open(path); OutputStream out = new FileOutputStream(dest)) {
                byte[] buf = new byte[16384];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            }
            return;
        }
        for (String child : children) copyTree(am, path + "/" + child, new File(dest, child));
    }

    private static void deleteTree(File f) {
        File[] kids = f.listFiles();
        if (kids != null) for (File k : kids) deleteTree(k);
        //noinspection ResultOfMethodCallIgnored
        f.delete();
    }

    static String displayName(File preset) {
        String n = preset.getName();
        n = n.substring(0, n.length() - ".milk".length());
        return n.replace('_', ' ');
    }

    static List<File> shuffled(List<File> list) {
        List<File> copy = new ArrayList<>(list);
        Collections.shuffle(copy);
        return copy;
    }
}
