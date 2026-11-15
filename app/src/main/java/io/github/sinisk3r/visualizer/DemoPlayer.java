package io.github.sinisk3r.visualizer;

import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.util.Log;

/**
 * Plays the demo songs in a loop, streamed (see DemoSongs). The visualizer hears them like any other app's
 * sound (Visualizer session 0 is the whole output mix). Main thread only.
 */
final class DemoPlayer {
    interface Listener {
        /** A song started (index), or playing stopped or failed (-1, with an error message or null). */
        void onDemo(int index, String error);
    }

    private final Listener listener;
    private MediaPlayer player;
    private int index = -1;

    DemoPlayer(Listener listener) {
        this.listener = listener;
    }

    boolean isOn() {
        return player != null;
    }

    int index() {
        return index;
    }

    void start() {
        if (player == null) play(0);
    }

    void stop() {
        release();
        index = -1;
        listener.onDemo(-1, null);
    }

    private void play(int i) {
        release();
        index = i;
        MediaPlayer p = new MediaPlayer();
        player = p;
        try {
            p.setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build());
            p.setDataSource(DemoSongs.url(i));
            p.setOnPreparedListener(mp -> {
                if (mp != player) return;
                mp.start();
                listener.onDemo(index, null);
            });
            p.setOnCompletionListener(mp -> {
                if (mp == player) play(DemoSongs.next(index));
            });
            p.setOnErrorListener((mp, what, extra) -> {
                if (mp != player) return true;
                Log.w("Visualizer", "demo song failed: " + what + "/" + extra);
                release();
                index = -1;
                listener.onDemo(-1, "Could not play the demo song. Is the TV online?");
                return true;
            });
            p.prepareAsync();
        } catch (Exception e) {
            Log.w("Visualizer", "demo song failed", e);
            release();
            index = -1;
            listener.onDemo(-1, "Could not play the demo song.");
        }
    }

    private void release() {
        MediaPlayer p = player;
        player = null;
        if (p != null) p.release();
    }
}
