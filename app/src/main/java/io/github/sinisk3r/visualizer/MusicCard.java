package io.github.sinisk3r.visualizer;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Typeface;
import android.media.AudioManager;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.TextUtils;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.List;
import java.util.function.BooleanSupplier;

/**
 * The music card (mockup A2, screens 1s-E and 1p): above the bar on the left while the bar is open with no other
 * card. On the left the playing app's album art, the full height of the card, with the app's name under it; on the
 * right the title, "Artist · Album", Previous and Next at the top right, and the scrubber along the bottom.
 *
 * Previous and Next need no permission (media keys through AudioManager). With notification access (granted once
 * in TV Settings, asked for only from the card's "See song and album art" row, after an explainer) the card shows
 * what plays, from the app's media session, and the scrubber (ScrubView): OK on it pauses or plays, Left/Right
 * seek 10 s (held: 30 s), with the target shown above the thumb and the seek made when the key is let go. Without
 * access there is no scrubber; the ask row takes its place. Nothing is stored or sent; notifications themselves
 * are never read (MusicListener is empty).
 *
 * Our own demo songs (DemoPlayer) have no media session, so the card ignores them.
 *
 * Remote: Up from any bar button lands on the scrubber (Previous when there is none); Up from the scrubber or the
 * ask row reaches Previous/Next, Left/Right between them, Down back to the scrubber or the ask row, then to the bar
 * button Up came from. Built once; updates only rewrite values.
 */
final class MusicCard {
    private static final String TAG = "Visualizer";
    private static final long FADE_MS = 140;
    /** A seek is made when the key is let go, or this long after the last step if the key-up never comes. */
    private static final long SEEK_COMMIT_MS = 800;

    private final Activity act;
    private final ViewGroup root;
    private final View bar, scrim;
    private final BooleanSupplier demoOn;
    private final AudioManager audio;
    private final MediaSessionManager sessions;
    private final ComponentName listener;
    private final SharedPreferences prefs;
    private final Handler ui = new Handler(Looper.getMainLooper());

    private final LinearLayout card;
    private final ImageView art;
    private final TextView song, who, src, askRow, hint;
    private final FrameLayout prev, next;
    /** GONE without access or without a session. */
    private final ScrubView scrub;
    /** The last seek's target and when (uptime), so quick steps build on it; -1: none. */
    private long seekTarget = -1, lastSeekAt;
    /** Where held Left/Right will land, not yet sent to the app; -1: none. */
    private long pendingSeek = -1;
    private final Runnable commitSeek = this::commitSeek;
    private final View explainer;
    private final TextView openSettings, notNow;

    /** The bar button Up came from; Down from the card returns there. */
    private View returnTo;
    private boolean shown, access;
    private MediaController controller;
    private Bitmap shownArt;
    private boolean listening;

    MusicCard(Activity act, ViewGroup root, View bar, View scrim, BooleanSupplier demoOn) {
        this.act = act;
        this.root = root;
        this.bar = bar;
        this.scrim = scrim;
        this.demoOn = demoOn;
        audio = act.getSystemService(AudioManager.class);
        sessions = act.getSystemService(MediaSessionManager.class);
        listener = new ComponentName(act, MusicListener.class);
        prefs = act.getSharedPreferences("music", Context.MODE_PRIVATE);

        card = new LinearLayout(act);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setBackgroundResource(R.drawable.card_bg);
        card.setPadding(dp(10), dp(10), dp(10), dp(10));
        card.setClipChildren(false);  // the seek pill draws above the scrubber
        card.setVisibility(View.GONE);
        // Raised clear of the bar buttons' labels (they fade in above a focused button), which leaves room for the
        // scrubber's hint line between the card and the bar.
        FrameLayout.LayoutParams cl = new FrameLayout.LayoutParams(dp(340), ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM | Gravity.START);
        cl.leftMargin = dp(48);
        cl.bottomMargin = dp(96);
        root.addView(card, cl);

        // Left: the cover, the card's full height, with the app's name under it.
        LinearLayout left = new LinearLayout(act);
        left.setOrientation(LinearLayout.VERTICAL);
        left.setGravity(Gravity.CENTER_HORIZONTAL);
        card.addView(left, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        art = new ImageView(act);
        art.setBackgroundResource(R.drawable.art_bg);
        art.setClipToOutline(true);
        art.setScaleType(ImageView.ScaleType.CENTER);
        art.setImageResource(R.drawable.ic_note);
        art.setImageTintList(android.content.res.ColorStateList.valueOf(0xFF74747A));
        left.addView(art, new LinearLayout.LayoutParams(dp(64), dp(64)));
        src = text(11, 0xFF74747A, false);
        src.setGravity(Gravity.CENTER_HORIZONTAL);
        src.setMaxWidth(dp(84));
        LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        sl.topMargin = dp(3);
        left.addView(src, sl);

        // Right: title and artist with Previous/Next beside them, the scrubber (or the ask row) at the bottom.
        LinearLayout right = new LinearLayout(act);
        right.setOrientation(LinearLayout.VERTICAL);
        right.setClipChildren(false);
        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f);
        rp.leftMargin = dp(12);
        card.addView(right, rp);

        LinearLayout top = new LinearLayout(act);
        top.setOrientation(LinearLayout.HORIZONTAL);
        right.addView(top, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout meta = new LinearLayout(act);
        meta.setOrientation(LinearLayout.VERTICAL);
        top.addView(meta, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        song = text(13, 0xFFECECEE, true);
        meta.addView(song);
        who = text(12, 0xFFA4A4AA, false);
        meta.addView(who);
        prev = button(top, R.id.m_prev, R.drawable.ic_prev, "Previous song", v -> previous());
        next = button(top, R.id.m_next, R.drawable.ic_next, "Next song", v -> next());

        right.addView(new View(act), new LinearLayout.LayoutParams(1, 0, 1f));  // pushes the rest down

        scrub = new ScrubView(act);
        scrub.setId(R.id.m_seek);
        scrub.setContentDescription("Song position: OK pauses or plays, left and right seek");
        scrub.setOnClickListener(v -> playPause());
        scrub.setVisibility(View.GONE);
        LinearLayout.LayoutParams scl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                scrub.wantedHeight());
        scl.topMargin = dp(4);
        right.addView(scrub, scl);

        askRow = text(12, 0xFFECECEE, false);
        askRow.setTextColor(act.getColorStateList(R.color.on_focus));
        askRow.setText("See song and album art");
        askRow.setId(R.id.m_ask);
        askRow.setBackgroundResource(R.drawable.card_row);
        askRow.setGravity(Gravity.CENTER_VERTICAL);
        askRow.setPadding(dp(8), 0, dp(8), 0);
        askRow.setFocusable(true);
        askRow.setClickable(true);
        askRow.setOnClickListener(v -> askForAccess());
        LinearLayout.LayoutParams rl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(24));
        rl.topMargin = dp(4);
        right.addView(askRow, rl);

        // The keys, under the card, only while the scrubber has focus.
        hint = text(11, 0xFF8A8A90, false);
        hint.setId(R.id.m_hint);
        hint.setVisibility(View.GONE);
        FrameLayout.LayoutParams hl = new FrameLayout.LayoutParams(dp(340), ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM | Gravity.START);
        hl.leftMargin = dp(48 + 10);
        hl.bottomMargin = dp(78);
        root.addView(hint, hl);
        scrub.setOnFocusChangeListener((v, has) -> {
            hint.animate().cancel();
            if (has && card.getVisibility() == View.VISIBLE) {
                hint.setAlpha(0f);
                hint.setVisibility(View.VISIBLE);
                hint.animate().alpha(1f).setDuration(FADE_MS).start();
            } else {
                hint.setVisibility(View.GONE);
                commitSeek();
            }
        });

        // The explainer (screen 1p): over everything, the bar hidden under it.
        FrameLayout ex = new FrameLayout(act);
        ex.setBackgroundColor(0x8C000000);
        ex.setVisibility(View.GONE);
        root.addView(ex, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        LinearLayout box = new LinearLayout(act);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackgroundResource(R.drawable.card_bg);
        box.setPadding(dp(18), dp(16), dp(18), dp(16));
        ex.addView(box, new FrameLayout.LayoutParams(dp(450), ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));
        TextView h = text(16, 0xFFECECEE, true);
        h.setSingleLine(false);
        h.setText("See the song and album art?");
        box.addView(h);
        TextView body = paragraph("To show what's playing in Spotify, YouTube Music and other apps, Visualizer needs "
                + "notification access. Android asks for it on one Settings screen: turn on Visualizer there, then "
                + "press Back.", 0xFFECECEE);
        box.addView(body, paraParams());
        TextView small = paragraph("The song details stay on this TV. Visualizer does not read or keep your "
                + "notifications. Play, pause and skip work without this.", 0xFFA4A4AA);
        box.addView(small, paraParams());
        LinearLayout btns = new LinearLayout(act);
        btns.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        bp.topMargin = dp(12);
        box.addView(btns, bp);
        openSettings = chip("Open Settings", R.id.m_open_settings, v -> openSettings());
        notNow = chip("Not now", R.id.m_not_now, v -> closeExplainer());
        btns.addView(openSettings, chipParams(0));
        btns.addView(notNow, chipParams(dp(6)));
        explainer = ex;
    }


    // --- shown with the bar ---

    /** The bar is open with no other card: fade the card in (its values first). */
    void show() {
        shown = true;
        update();
        if (card.getVisibility() == View.VISIBLE) return;
        card.animate().cancel();
        card.setAlpha(0f);
        card.setVisibility(View.VISIBLE);
        card.animate().alpha(1f).setDuration(FADE_MS).withLayer().start();
    }

    /** Another card opened, or the menu closed. Hidden views are GONE (a visible one costs a 4K compose). */
    void hide() {
        shown = false;
        commitSeek();
        card.animate().cancel();
        card.setVisibility(View.GONE);
        hint.animate().cancel();
        hint.setVisibility(View.GONE);
        if (explainer.getVisibility() == View.VISIBLE) closeExplainer();
        stopListening();
        returnTo = null;
    }

    boolean isShown() {
        return shown;
    }

    /** The access explainer is up: the menu counts it as a card (the longer idle wait). */
    boolean explainerShown() {
        return explainerOpen();
    }

    /** Once a second while the bar is open: access may have changed in Settings, the app may have changed. */
    void tick() {
        if (shown) update();
    }

    /** Back: closes the explainer. False when the menu should handle Back. */
    boolean back() {
        if (explainer.getVisibility() != View.VISIBLE) return false;
        closeExplainer();
        return true;
    }

    /** Keys while the bar is open with no other card. True when used. */
    boolean handleKey(KeyEvent e, View focused) {
        int k = e.getKeyCode();
        if (shown && e.getAction() == KeyEvent.ACTION_UP && focused == scrub
                && (k == KeyEvent.KEYCODE_DPAD_LEFT || k == KeyEvent.KEYCODE_DPAD_RIGHT)) {
            commitSeek();  // let go: the song jumps now
            return true;
        }
        if (!shown || e.getAction() != KeyEvent.ACTION_DOWN) return explainerOpen() && isArrow(k);
        if (explainerOpen()) {
            if (k == KeyEvent.KEYCODE_DPAD_LEFT) openSettings.requestFocus();
            else if (k == KeyEvent.KEYCODE_DPAD_RIGHT) notNow.requestFocus();
            return isArrow(k);
        }
        if (focused == null) return false;
        if (isInside(focused, bar)) {
            if (k == KeyEvent.KEYCODE_DPAD_LEFT || k == KeyEvent.KEYCODE_DPAD_RIGHT) {
                // At the bar's ends Android's focus search can find a card row (it reaches past the bar's left
                // end): only Up enters the card.
                View n = focused.focusSearch(k == KeyEvent.KEYCODE_DPAD_LEFT ? View.FOCUS_LEFT : View.FOCUS_RIGHT);
                return n != null && isInside(n, card);
            }
            if (k != KeyEvent.KEYCODE_DPAD_UP) return false;
            returnTo = focused;
            (scrubShown() ? scrub : prev).requestFocus();
            return true;
        }
        if (!isInside(focused, card)) return false;
        boolean skip = focused == prev || focused == next;
        boolean askShown = askRow.getVisibility() == View.VISIBLE;
        switch (k) {
            case KeyEvent.KEYCODE_DPAD_UP:
                if (!skip) prev.requestFocus();
                return true;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                if (skip && scrubShown()) scrub.requestFocus();
                else if (skip && askShown) askRow.requestFocus();
                else toBar();
                return true;
            case KeyEvent.KEYCODE_DPAD_LEFT:
                if (focused == scrub) seek(-1, e);
                else if (focused == next) prev.requestFocus();
                return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                if (focused == scrub) seek(1, e);
                else if (focused == prev) next.requestFocus();
                return true;
            default:
                return false;
        }
    }

    private boolean scrubShown() {
        return scrub.getVisibility() == View.VISIBLE;
    }

    private void toBar() {
        View back = returnTo != null && returnTo.isShown() ? returnTo : bar.findViewById(R.id.b_fav);
        if (back != null) back.requestFocus();
    }

    private boolean explainerOpen() {
        return explainer.getVisibility() == View.VISIBLE;
    }

    private static boolean isArrow(int k) {
        return k == KeyEvent.KEYCODE_DPAD_UP || k == KeyEvent.KEYCODE_DPAD_DOWN || k == KeyEvent.KEYCODE_DPAD_LEFT
                || k == KeyEvent.KEYCODE_DPAD_RIGHT;
    }

    // --- controls ---

    /** Commands to the playing app's session. Tests swap it in (Robolectric sessions do not deliver them). */
    interface Transport {
        void send(MediaController c, String command, long pos);
    }

    Transport transport = (c, command, pos) -> {
        MediaController.TransportControls t = c.getTransportControls();
        switch (command) {
            case "previous": t.skipToPrevious(); break;
            case "next": t.skipToNext(); break;
            case "pause": t.pause(); break;
            case "play": t.play(); break;
            case "seek": t.seekTo(pos); break;
            default: break;
        }
    };

    private void previous() {
        MediaController c = controller;
        if (c != null) transport.send(c, "previous", 0);
        else mediaKey(KeyEvent.KEYCODE_MEDIA_PREVIOUS);
    }

    private void next() {
        MediaController c = controller;
        if (c != null) transport.send(c, "next", 0);
        else mediaKey(KeyEvent.KEYCODE_MEDIA_NEXT);
    }

    /** OK on the scrubber. */
    private void playPause() {
        MediaController c = controller;
        if (c != null) {
            if (NowPlaying.state(stateOf(c)) == NowPlaying.PLAYING) transport.send(c, "pause", 0);
            else transport.send(c, "play", 0);
        } else {
            mediaKey(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE);
        }
        // The app's new state settles a moment later.
        ui.postDelayed(this::update, 400);
        ui.postDelayed(this::update, 1200);
    }

    /** To whichever app holds the active media session. Needs no permission. */
    private void mediaKey(int code) {
        audio.dispatchMediaKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, code));
        audio.dispatchMediaKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, code));
    }

    /**
     * Left (-1) or Right (+1) on the scrubber: 10 s, or 30 s once the key has been held ~1.5 s. Only moves the
     * target (shown in the pill over the thumb); the seek is made on key-up, so holding the key does not stutter.
     */
    private void seek(int dir, KeyEvent e) {
        MediaController c = controller;
        long dur = durationOf(c);
        PlaybackState st = c == null ? null : c.getPlaybackState();
        if (c == null || !NowPlaying.canSeek(access, st == null ? 0 : st.getActions(), dur)) return;
        long from = pendingSeek >= 0 ? pendingSeek
                : NowPlaying.seekBase(seekTarget, lastSeekAt, SystemClock.uptimeMillis(), positionOf(c, dur));
        long step = NowPlaying.seekStep(e.getRepeatCount() > 0 ? e.getEventTime() - e.getDownTime() : 0);
        pendingSeek = NowPlaying.seekTarget(from, dir * step, dur);
        scrub.setPill(NowPlaying.time(pendingSeek));
        showPosition(pendingSeek, dur, NowPlaying.state(stateOf(c)) != NowPlaying.PLAYING);
        ui.removeCallbacks(commitSeek);
        ui.postDelayed(commitSeek, SEEK_COMMIT_MS);
    }

    /** Sends the held seek to the app, if there is one. */
    private void commitSeek() {
        ui.removeCallbacks(commitSeek);
        if (pendingSeek < 0) return;
        long target = pendingSeek;
        pendingSeek = -1;
        scrub.setPill(null);
        MediaController c = controller;
        if (c == null) return;
        transport.send(c, "seek", target);
        seekTarget = target;
        lastSeekAt = SystemClock.uptimeMillis();
    }

    private static long durationOf(MediaController c) {
        MediaMetadata m = c == null ? null : c.getMetadata();
        return m == null ? 0 : m.getLong(MediaMetadata.METADATA_KEY_DURATION);
    }

    private static long positionOf(MediaController c, long dur) {
        PlaybackState s = c.getPlaybackState();
        if (s == null) return 0;
        return NowPlaying.position(s.getPosition(), s.getLastPositionUpdateTime(), s.getPlaybackSpeed(),
                s.getState() == PlaybackState.STATE_PLAYING, SystemClock.elapsedRealtime(), dur);
    }

    /** Rewrites the scrubber's values only (it redraws itself, nothing is laid out again). */
    private void showPosition(long pos, long dur, boolean paused) {
        if (dur > 0) {
            scrub.set(NowPlaying.progress(pos, dur), NowPlaying.elapsedLabel(pos, paused), NowPlaying.time(dur),
                    paused);
        } else {  // length unknown (a stream): play state only
            scrub.set(-1, paused ? "Paused" : "", "", paused);
        }
    }

    // --- notification access ---

    private void askForAccess() {
        if (prefs.getBoolean("explained", false)) {
            openSettings();
            return;
        }
        bar.setVisibility(View.GONE);
        scrim.setVisibility(View.GONE);
        card.setVisibility(View.GONE);
        explainer.animate().cancel();
        explainer.setAlpha(0f);
        explainer.setVisibility(View.VISIBLE);  // VISIBLE before focus
        explainer.animate().alpha(1f).setDuration(FADE_MS).withLayer().start();
        openSettings.requestFocus();
    }

    private void closeExplainer() {
        explainer.animate().cancel();
        explainer.setVisibility(View.GONE);
        bar.setVisibility(View.VISIBLE);
        scrim.setVisibility(View.VISIBLE);
        if (shown) {
            card.setVisibility(View.VISIBLE);
            askRow.requestFocus();
        }
    }

    private void openSettings() {
        prefs.edit().putBoolean("explained", true).apply();
        if (explainerOpen()) closeExplainer();
        try {
            act.startActivity(new Intent(android.provider.Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS));
        } catch (ActivityNotFoundException e) {
            toast("Open TV Settings, Apps, Special app access, Notification access, and turn on Visualizer");
        }
    }

    private boolean checkAccess() {
        String enabled = android.provider.Settings.Secure.getString(act.getContentResolver(),
                "enabled_notification_listeners");
        return NowPlaying.hasAccess(enabled, act.getPackageName());
    }

    // --- media sessions (with access) ---

    private final MediaSessionManager.OnActiveSessionsChangedListener sessionsChanged = list -> choose(list);

    private final MediaController.Callback callback = new MediaController.Callback() {
        @Override
        public void onMetadataChanged(MediaMetadata metadata) {
            update();
        }

        @Override
        public void onPlaybackStateChanged(PlaybackState state) {
            update();
        }

        @Override
        public void onSessionDestroyed() {
            setController(null);
            update();
        }
    };

    private void startListening() {
        if (listening) return;
        try {
            sessions.addOnActiveSessionsChangedListener(sessionsChanged, listener, ui);
            listening = true;
        } catch (SecurityException e) {
            Log.w(TAG, "music: no session access", e);
        }
    }

    private void stopListening() {
        if (listening) {
            sessions.removeOnActiveSessionsChangedListener(sessionsChanged);
            listening = false;
        }
        setController(null);
    }

    /** Re-picks from the active sessions: which one plays can change without the list changing. */
    private void rescan() {
        try {
            choose(sessions.getActiveSessions(listener));
        } catch (SecurityException e) {  // access just turned off
            setController(null);
        }
    }

    private void choose(List<MediaController> list) {
        if (list == null) list = java.util.Collections.emptyList();
        String[] pkgs = new String[list.size()];
        int[] states = new int[list.size()];
        for (int i = 0; i < pkgs.length; i++) {
            pkgs[i] = list.get(i).getPackageName();
            states[i] = stateOf(list.get(i));
        }
        int i = NowPlaying.pick(pkgs, states, act.getPackageName());
        MediaController c = i < 0 ? null : list.get(i);
        if (c == null || controller == null || !c.getSessionToken().equals(controller.getSessionToken())) {
            setController(c);
        }
        if (shown) render();
    }

    private void setController(MediaController c) {
        if (controller != null) controller.unregisterCallback(callback);
        if (c != controller) {  // a held seek was for the old song
            ui.removeCallbacks(commitSeek);
            pendingSeek = seekTarget = -1;
            scrub.setPill(null);
        }
        controller = c;
        if (c != null) c.registerCallback(callback, ui);
    }

    private static int stateOf(MediaController c) {
        PlaybackState s = c.getPlaybackState();
        return s == null ? PlaybackState.STATE_NONE : s.getState();
    }

    // --- values ---

    private void update() {
        if (!shown) return;
        boolean now = checkAccess();
        if (now != access) {
            access = now;
            if (access) startListening();
            else stopListening();
        } else if (access && !listening) {
            startListening();
        }
        if (access) rescan();
        else render();
    }

    /** Rewrites the card's values in place; never rebuilds it. */
    private void render() {
        MediaController c = controller;
        boolean withScrub = false;
        if (access && c != null) {
            PlaybackState st = c.getPlaybackState();
            withScrub = NowPlaying.showScrubber(stateOf(c), st == null ? 0 : st.getActions(), durationOf(c));
        }
        if (withScrub) scrub.setVisibility(View.VISIBLE);  // VISIBLE before focus
        // Before a focused view goes (a GONE view drops focus): to the scrubber if it stays, else to Previous.
        if ((access && askRow.isFocused()) || (!withScrub && scrub.isFocused())) {
            (withScrub ? scrub : prev).requestFocus();
        }
        askRow.setVisibility(access ? View.GONE : View.VISIBLE);
        Bitmap bmp = null;
        if (c != null) {
            MediaMetadata m = c.getMetadata();
            String app = appName(c.getPackageName());
            if (m != null) {
                setIfChanged(song, NowPlaying.title(m.getString(MediaMetadata.METADATA_KEY_TITLE),
                        m.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE), app));
                String artist = m.getString(MediaMetadata.METADATA_KEY_ARTIST);
                if (TextUtils.isEmpty(artist)) artist = m.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST);
                setIfChanged(who, NowPlaying.who(artist, m.getString(MediaMetadata.METADATA_KEY_ALBUM)));
                bmp = m.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART);
                if (bmp == null) bmp = m.getBitmap(MediaMetadata.METADATA_KEY_ART);
                if (bmp == null) bmp = m.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON);
            } else {
                setIfChanged(song, NowPlaying.title(null, null, app));
                setIfChanged(who, "");
            }
            setIfChanged(src, app);
        } else if (access) {
            setIfChanged(song, "Nothing playing");
            setIfChanged(who, "Start music in any app");
            setIfChanged(src, "");
        } else {
            // Without access Android does not say which app plays: Previous and Next go to whichever it is (our
            // demo songs excluded: they have no session, so the keys would not reach them).
            setIfChanged(song, "Music controls");
            setIfChanged(who, "For the app playing music");
            setIfChanged(src, "");
        }
        who.setVisibility(who.getText().length() == 0 ? View.GONE : View.VISIBLE);
        if (withScrub) {
            long dur = durationOf(c);
            boolean paused = NowPlaying.state(stateOf(c)) != NowPlaying.PLAYING;
            // While a seek is held or steps follow quickly, show where they lead, not the app's lagging report.
            long pos = pendingSeek >= 0 ? pendingSeek
                    : NowPlaying.seekBase(seekTarget, lastSeekAt, SystemClock.uptimeMillis(), positionOf(c, dur));
            showPosition(pos, dur, paused);
            setIfChanged(hint, NowPlaying.scrubHint(paused));
        } else {
            scrub.setVisibility(View.GONE);
            pendingSeek = seekTarget = -1;
            scrub.setPill(null);
            ui.removeCallbacks(commitSeek);
        }
        if (bmp != shownArt) {
            shownArt = bmp;
            if (bmp != null) {
                art.setScaleType(ImageView.ScaleType.CENTER_CROP);
                art.setImageTintList(null);
                art.setImageBitmap(bmp);
            } else {
                art.setScaleType(ImageView.ScaleType.CENTER);
                art.setImageResource(R.drawable.ic_note);
                art.setImageTintList(android.content.res.ColorStateList.valueOf(0xFF74747A));
            }
        }
    }

    private String appName(String pkg) {
        PackageManager pm = act.getPackageManager();
        try {
            return pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString();
        } catch (PackageManager.NameNotFoundException e) {
            return NowPlaying.appFromPackage(pkg);
        }
    }

    // --- views ---

    /** Previous or Next: a round icon button, top right. */
    private FrameLayout button(ViewGroup parent, int id, int icon, String label, View.OnClickListener click) {
        FrameLayout cell = new FrameLayout(act);
        cell.setId(id);
        cell.setFocusable(true);
        cell.setClickable(true);
        cell.setBackgroundResource(R.drawable.bar_button);
        cell.setContentDescription(label);
        cell.setOnClickListener(click);
        ImageView iv = new ImageView(act);
        iv.setImageResource(icon);
        iv.setImageTintList(act.getColorStateList(R.color.on_focus));
        iv.setDuplicateParentStateEnabled(true);
        cell.addView(iv, new FrameLayout.LayoutParams(dp(14), dp(14), Gravity.CENTER));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(28), dp(28));
        lp.leftMargin = dp(4);
        parent.addView(cell, lp);
        return cell;
    }

    private TextView text(int sp, int color, boolean bold) {
        TextView t = new TextView(act);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        t.setSingleLine(true);
        t.setEllipsize(TextUtils.TruncateAt.END);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    private TextView paragraph(String s, int color) {
        TextView t = new TextView(act);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        t.setTextColor(color);
        t.setLineSpacing(0, 1.2f);
        t.setText(s);
        return t;
    }

    private LinearLayout.LayoutParams paraParams() {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        p.topMargin = dp(8);
        return p;
    }

    private TextView chip(String label, int id, View.OnClickListener click) {
        TextView t = new TextView(act);
        t.setId(id);
        t.setText(label);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        t.setTextColor(act.getColorStateList(R.color.on_focus));
        t.setBackgroundResource(R.drawable.chip_bg);
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp(13), 0, dp(13), 0);
        t.setFocusable(true);
        t.setClickable(true);
        t.setOnClickListener(click);
        return t;
    }

    private LinearLayout.LayoutParams chipParams(int left) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(28));
        p.leftMargin = left;
        return p;
    }

    private static void setIfChanged(TextView t, CharSequence s) {
        if (!TextUtils.equals(s, t.getText())) t.setText(s);
    }

    private static boolean isInside(View v, View ancestor) {
        for (ViewParent p = v.getParent(); p != null; p = p.getParent()) {
            if (p == ancestor) return true;
        }
        return v == ancestor;
    }

    private void toast(String msg) {
        Toast.makeText(act, msg, Toast.LENGTH_LONG).show();
    }

    private int dp(int v) {
        return Math.round(v * act.getResources().getDisplayMetrics().density);
    }
}
