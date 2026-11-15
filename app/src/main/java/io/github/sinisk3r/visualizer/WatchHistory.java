package io.github.sinisk3r.visualizer;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What was watched on this TV: one row per preset and day, with the seconds it was shown (menu closed) and when it
 * was last shown. Kept only on the TV, in app storage; nothing is sent anywhere. Feeds the smart lists: Most watched
 * (last month or three months), New to you (never or rarely shown) and Recently played. Plain logic, no Android
 * types, so it is tested on the JVM. Thread-safe: the UI thread adds, the save thread writes.
 */
final class WatchHistory {
    /** A preset counts as watched once it was on screen this long with the menu closed. */
    static final int WATCHED_SECONDS = 10;
    /** Shown less than this in total: still "new to you". */
    static final int RARELY_SECONDS = 60;
    /** Rows older than this are dropped (the longest list looks back three months). */
    static final int KEEP_DAYS = 92;
    static final long DAY_MS = 24L * 60 * 60 * 1000;

    private static final class Row {
        final String name;
        final long day;
        long seconds;
        long lastMs;

        Row(String name, long day, long seconds, long lastMs) {
            this.name = name;
            this.day = day;
            this.seconds = seconds;
            this.lastMs = lastMs;
        }
    }

    /** Key: day + tab + name. Insertion order is kept, so a file round-trips unchanged. */
    private final Map<String, Row> rows = new LinkedHashMap<>();
    private boolean dirty;

    /** Records a showing that ended at `endMs`. Shorter than {@link #WATCHED_SECONDS} does not count. */
    synchronized boolean add(String name, long endMs, long seconds) {
        if (name == null || seconds < WATCHED_SECONDS) return false;
        long day = Math.floorDiv(endMs, DAY_MS);
        String key = day + "\t" + name;
        Row r = rows.get(key);
        if (r == null) rows.put(key, new Row(name, day, seconds, endMs));
        else {
            r.seconds += seconds;
            r.lastMs = Math.max(r.lastMs, endMs);
        }
        dirty = true;
        return true;
    }

    synchronized int size() {
        return rows.size();
    }

    /** True once since the last {@link #write}: there is something new to save. */
    synchronized boolean isDirty() {
        return dirty;
    }

    /** Seconds shown in total (every day kept). */
    synchronized long totalSeconds(String name) {
        long s = 0;
        for (Row r : rows.values()) if (r.name.equals(name)) s += r.seconds;
        return s;
    }

    /** Most seconds shown in the last `days` days (today included), most first; ties by name. */
    synchronized List<String> mostWatched(long nowMs, int days, int limit) {
        long from = Math.floorDiv(nowMs, DAY_MS) - days + 1;
        Map<String, Long> sum = new HashMap<>();
        for (Row r : rows.values()) {
            if (r.day >= from) sum.merge(r.name, r.seconds, Long::sum);
        }
        List<Map.Entry<String, Long>> es = new ArrayList<>(sum.entrySet());
        es.sort((a, b) -> {
            int c = Long.compare(b.getValue(), a.getValue());
            return c != 0 ? c : a.getKey().compareTo(b.getKey());
        });
        List<String> out = new ArrayList<>();
        for (Map.Entry<String, Long> e : es) {
            if (out.size() >= limit) break;
            out.add(e.getKey());
        }
        return out;
    }

    /** Last shown first, each preset once. */
    synchronized List<String> recentlyPlayed(int limit) {
        Map<String, Long> last = new HashMap<>();
        for (Row r : rows.values()) last.merge(r.name, r.lastMs, Math::max);
        List<Map.Entry<String, Long>> es = new ArrayList<>(last.entrySet());
        es.sort((a, b) -> {
            int c = Long.compare(b.getValue(), a.getValue());
            return c != 0 ? c : a.getKey().compareTo(b.getKey());
        });
        List<String> out = new ArrayList<>();
        for (Map.Entry<String, Long> e : es) {
            if (out.size() >= limit) break;
            out.add(e.getKey());
        }
        return out;
    }

    /**
     * The candidates never or rarely shown (under {@link #RARELY_SECONDS} in total), never-shown ones first, each
     * group in the order given. So newer presets get the same chance as the ones already seen often.
     */
    synchronized List<String> newToYou(Collection<String> candidates) {
        Map<String, Long> total = new HashMap<>();
        for (Row r : rows.values()) total.merge(r.name, r.seconds, Long::sum);
        List<String> never = new ArrayList<>(), rarely = new ArrayList<>();
        for (String c : candidates) {
            Long s = total.get(c);
            if (s == null) never.add(c);
            else if (s < RARELY_SECONDS) rarely.add(c);
        }
        never.addAll(rarely);
        return never;
    }

    /** Drops rows older than {@link #KEEP_DAYS}. */
    synchronized void prune(long nowMs) {
        long from = Math.floorDiv(nowMs, DAY_MS) - KEEP_DAYS + 1;
        for (Iterator<Row> it = rows.values().iterator(); it.hasNext(); ) {
            if (it.next().day < from) {
                it.remove();
                dirty = true;
            }
        }
    }

    /** One row per line: day, seconds, last shown (ms), file name; tab separated. */
    synchronized void write(Writer out) throws IOException {
        for (Row r : rows.values()) {
            out.write(r.day + "\t" + r.seconds + "\t" + r.lastMs + "\t" + r.name + "\n");
        }
        out.flush();
        dirty = false;
    }

    /** Reads what {@link #write} wrote; bad lines are skipped. */
    static WatchHistory read(Reader in) throws IOException {
        WatchHistory h = new WatchHistory();
        BufferedReader b = in instanceof BufferedReader ? (BufferedReader) in : new BufferedReader(in);
        for (String line; (line = b.readLine()) != null; ) {
            String[] p = line.split("\t", 4);
            if (p.length < 4 || p[3].isEmpty()) continue;
            try {
                long day = Long.parseLong(p[0]);
                h.rows.put(day + "\t" + p[3], new Row(p[3], day, Long.parseLong(p[1]), Long.parseLong(p[2])));
            } catch (NumberFormatException ignored) {
                // a torn line from a crash mid-write
            }
        }
        return h;
    }
}
