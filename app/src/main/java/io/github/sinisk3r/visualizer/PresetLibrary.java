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
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Finds presets, sorted two levels deep: style (top folder) and sub-style (the folder under it; "General" for
 * presets directly in a style folder). Two sources, merged; a preset in both counts once (the user copy wins):
 * 1. The pack bundled in the APK (assets/presets, assets/textures), copied to internal storage on first run
 *    because projectM reads plain files.
 * 2. An optional user folder, Android/data/<package>/files/presets, for anyone who wants the full pack
 *    (push it with adb or a file manager). Its textures go in .../files/textures.
 */
final class PresetLibrary {
    static final String ALL = "All";
    static final String FAVOURITES = "★ Favourites";
    static final String GENERAL = "General";

    final File textureDir;
    /** Category name to preset files; "All" first, then folders in name order. */
    final TreeMap<String, List<File>> byCategory = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
    final List<String> categories = new ArrayList<>();
    /** Style to sub-style to presets. */
    final Map<String, TreeMap<String, List<File>>> subs = new HashMap<>();
    /** Every preset once, for Favourites. */
    final List<File> all = new ArrayList<>();
    private final Map<File, String> subOf = new HashMap<>();
    private final Set<String> seen = new HashSet<>();
    final boolean hasFullPack;
    /** Presets whose custom shapes draw so many instances that they crawl on TV hardware (assets/heavy.txt,
     *  from tools/make-heavy-list.py). File names. */
    final Set<String> heavy = new HashSet<>();
    /** How much each preset follows the music (assets/reactivity.txt, from tools/make-reactivity-list.py):
     *  DEAF, WAVE_ONLY, SOME or STRONG. File names; presets not listed are SOME. */
    static final int DEAF = 0, WAVE_ONLY = 1, SOME = 2, STRONG = 3;
    private final Map<String, Integer> reactivity = new HashMap<>();

    PresetLibrary(Context ctx) throws IOException {
        File root = new File(ctx.getFilesDir(), "bundle");
        unpackIfNeeded(ctx, root);
        textureDir = new File(root, "textures");

        File user = PackDownloader.userPresetDir(ctx);
        hasFullPack = PackDownloader.isInstalled(ctx);
        loadFixes(new File(root, "fixes"));
        if (user != null) scan(user, all);
        scan(new File(root, "presets"), all);
        categories.add(ALL);
        categories.addAll(byCategory.keySet());
        byCategory.put(ALL, all);
        loadHeavy(ctx.getAssets());
        loadReactivity(ctx.getAssets());
    }

    private void loadReactivity(AssetManager assets) {
        try (java.io.BufferedReader in = new java.io.BufferedReader(new java.io.InputStreamReader(
                assets.open("reactivity.txt"), java.nio.charset.StandardCharsets.UTF_8))) {
            for (String line; (line = in.readLine()) != null; ) {
                int tab = line.lastIndexOf('\t');
                if (tab > 0 && line.charAt(0) >= '0' && line.charAt(0) <= '3') {
                    reactivity.put(line.substring(tab + 1), line.charAt(0) - '0');
                }
            }
        } catch (IOException e) {
            android.util.Log.w("Visualizer", "no reactivity list", e);
        }
    }

    int reactivityOf(File f) {
        Integer r = reactivity.get(f.getName());
        return r != null ? r : SOME;
    }

    boolean isDeaf(File f) {
        return reactivityOf(f) == DEAF;
    }

    private void loadHeavy(AssetManager assets) {
        try (java.io.BufferedReader in = new java.io.BufferedReader(
                new java.io.InputStreamReader(assets.open("heavy.txt"), java.nio.charset.StandardCharsets.UTF_8))) {
            for (String line; (line = in.readLine()) != null; ) {
                int tab = line.indexOf('\t');
                if (tab > 0) heavy.add(line.substring(tab + 1));
            }
        } catch (IOException e) {
            android.util.Log.w("Visualizer", "no heavy list", e);
        }
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
                String style = top.getName();
                File[] kids = top.listFiles();
                if (kids == null) continue;
                for (File k : kids) {
                    List<File> list = new ArrayList<>();
                    String sub = k.isDirectory() ? k.getName() : GENERAL;
                    if (k.isDirectory()) collect(k, list);
                    else if (isPreset(k)) list.add(k);
                    for (File f : list) add(style, sub, f, all);
                }
            } else if (isPreset(top)) {
                add("Other", GENERAL, top, all);
            }
        }
    }

    /** Fixed copies of presets that fail to parse in projectM 4 (assets/fixes, from Cream of the Crop pull request
     *  #7; patches/presets). They replace the broken copy wherever the preset is found. File name to file. */
    private final Map<String, File> fixes = new HashMap<>();

    private void loadFixes(File dir) {
        File[] files = dir.listFiles();
        if (files != null) for (File f : files) fixes.put(f.getName(), f);
    }

    private void add(String style, String sub, File f, List<File> all) {
        if (!seen.add(f.getName())) return;  // already found in an earlier source
        File fixed = fixes.get(f.getName());
        if (fixed != null) f = fixed;
        byCategory.computeIfAbsent(style, k -> new ArrayList<>()).add(f);
        subs.computeIfAbsent(style, k -> new TreeMap<>(String.CASE_INSENSITIVE_ORDER))
                .computeIfAbsent(sub, k -> new ArrayList<>()).add(f);
        subOf.put(f, sub);
        all.add(f);
    }

    String subStyleOf(File f) {
        String s = subOf.get(f);
        return s == null ? GENERAL : s;
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
        copyTree(am, "fixes", new File(root, "fixes"));
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
        return masked(n.replace('_', ' '));
    }

    /** Swear words in preset names (272 of the 9,795 in the full pack), masked on screen as "f**k" so the app
     *  suits a family TV and Play's "Everyone" rating. Matched inside words too ("mindfuck"). File names stay. */
    private static final java.util.regex.Pattern SWEARING = java.util.regex.Pattern.compile(
            "fuck|shit|cunt|whore|slut|asshole|bitch|bastard", java.util.regex.Pattern.CASE_INSENSITIVE);

    static String masked(String name) {
        java.util.regex.Matcher m = SWEARING.matcher(name);
        if (!m.find()) return name;
        StringBuilder out = new StringBuilder();
        int last = 0;
        do {
            String w = m.group();
            out.append(name, last, m.start()).append(w.charAt(0));
            for (int i = 1; i < w.length() - 1; i++) out.append('*');
            out.append(w.charAt(w.length() - 1));
            last = m.end();
        } while (m.find());
        return out.append(name.substring(last)).toString();
    }

    /** Play weights: strongly reactive presets come up about twice as often as most, waveform-only ones half
     *  as often. */
    private static double weight(int reactivity) {
        switch (reactivity) {
            case STRONG: return 2.0;
            case WAVE_ONLY: case DEAF: return 0.5;
            default: return 1.0;
        }
    }

    /** A weighted shuffle (Efraimidis-Spirakis: sort by u^(1/w)). Every preset still appears once, but heavier
     *  ones tend to come earlier, so they play more often in the hours it takes to get through a long list. */
    List<File> shuffled(List<File> list) {
        java.util.Random rnd = new java.util.Random();
        int n = list.size();
        double[] key = new double[n];
        Integer[] idx = new Integer[n];
        for (int i = 0; i < n; i++) {
            idx[i] = i;
            key[i] = Math.log(1.0 - rnd.nextDouble()) / weight(reactivityOf(list.get(i)));  // log(u)/w
        }
        java.util.Arrays.sort(idx, (a, b) -> Double.compare(key[b], key[a]));
        List<File> out = new ArrayList<>(n);
        for (Integer i : idx) out.add(list.get(i));
        return out;
    }
}
