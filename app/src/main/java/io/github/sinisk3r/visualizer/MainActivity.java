package io.github.sinisk3r.visualizer;

import android.Manifest;
import android.app.Activity;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.media.audiofx.Visualizer;
import android.opengl.GLSurfaceView;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.widget.TextView;

import java.io.File;
import java.util.List;

/**
 * Full-screen MilkDrop visuals that follow whatever the device is playing (Spotify, YouTube, ...).
 * Remote: Left/Right = previous/next preset, Up/Down = style (category), 0-9 = channel number, OK = the transport
 * bar (MenuPanel). A now-playing tag shows top right on every preset change ("Show what's playing").
 */
public class MainActivity extends Activity implements VizRenderer.Listener, MenuPanel.Host, ZeroPanel.Host {
    private static final String TAG = "Visualizer";
    private static final int REQ_AUDIO = 1;
    private static final long OVERLAY_MS = 4000;

    private GLSurfaceView glView;
    private VizRenderer renderer;
    private TextView title, subtitle, hint;
    private View overlay;
    /** Frame rate of the visuals, for the settings card. */
    private final FpsMeter fpsMeter = new FpsMeter();
    private volatile int pictureHeight;
    private volatile String shownCategory = PresetLibrary.ALL;
    private volatile long shownAt = android.os.SystemClock.uptimeMillis();
    /** The preset the tag last showed, so a settings refresh of the same preset does not show it again. */
    private File taggedPreset;
    /** Once a second while the bar is up: clock, progress, fps. */
    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (menu != null) menu.tick();
            ui.removeCallbacks(this);
            if (menu != null && menu.isOpen()) ui.postDelayed(this, 1000);
        }
    };
    private Visualizer audio;
    private SharedPreferences prefs;
    private Settings settings;
    private MenuPanel menu;
    /** Last state the renderer reported, for the menu (which runs on the UI thread). */
    private volatile File shownPreset;
    private volatile boolean shownLocked;
    private final Handler ui = new Handler(Looper.getMainLooper());
    // GONE once faded, not just transparent: any visible view keeps the app window as a second layer over
    // the visuals, and this TV's display hardware then hands the whole 4K picture to the GPU to compose.
    private final Runnable hideOverlay = () -> overlay.animate().alpha(0f).setDuration(600)
            .withEndAction(() -> {
                overlay.setVisibility(View.GONE);
                updateCovered();
            }).start();

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setContentView(R.layout.activity_main);
        overlay = findViewById(R.id.overlay);
        title = findViewById(R.id.title);
        subtitle = findViewById(R.id.subtitle);
        hint = findViewById(R.id.hint);
        prefs = getSharedPreferences("state", MODE_PRIVATE);
        settings = new Settings(this);
        if (settings.relearning) {
            Log.i(TAG, "renderer updated: forgot learned picture sizes");
            android.widget.Toast.makeText(this, "Updated: picture sizes will be measured again for this version.",
                    android.widget.Toast.LENGTH_LONG).show();
        }
        settings.logLearned();
        logDevice();

        // Finding ~10,000 preset files takes 1-2 s on TV storage: do it off the main thread, then start drawing.
        new Thread(() -> {
            try {
                PresetLibrary library = new PresetLibrary(this);
                WatchHistory h = loadHistory();
                List<String> featured = loadFeatured();
                ui.post(() -> {
                    history = h;
                    lists = new SmartLists(new SmartLists.Source() {
                        public List<File> all() { return library.all; }
                        public File byName(String name) { return library.byName(name); }
                        public boolean hidden(File f) { return settings.isHidden(f.getName()); }
                    }, h, featured);
                    startVisuals(library);
                });
            } catch (Exception e) {
                Log.e(TAG, "preset unpack failed", e);
                onError("Could not unpack presets: " + e.getMessage());
            }
        }, "preset-scan").start();

        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_AUDIO);
        }
    }

    /** Started by the adb test hook: play state is not saved. */
    private boolean testRun;

    private PresetLibrary library;
    /** Channel number being typed on the remote's number keys. */
    private final ChannelEntry typed = new ChannelEntry();
    private static final long NUMBER_WAIT_MS = 1800;
    private final Runnable playTyped = this::playTyped;
    /** "Shuffle this one only?" after picking a set with Shuffle on Everything (ShuffleAsk). */
    private final ShuffleAsk shuffleAsk = new ShuffleAsk();
    /** UI thread: a set was just picked (Up/Down, Find, the 0-list); the next preset the user asked for asks. */
    private boolean picked;

    private void startVisuals(PresetLibrary library) {
        if (isDestroyed()) return;
        this.library = library;
        // Test hook (adb): start on one preset, held, at a fixed picture height, without touching saved settings:
        // am start -n .../.MainActivity --es preset <path on the TV> --ei height 480
        String testPreset = getIntent().getStringExtra("preset");
        testRun = testPreset != null;
        // --ei height: 0 Smooth, 1 Sharp, else a fixed height (this run only).
        if (getIntent().hasExtra("height")) settings.overrideHeight(getIntent().getIntExtra("height", 0));
        // Test hook: play the demo songs (tests without a music app: am start ... --ez demo true).
        if (getIntent().getBooleanExtra("demo", false)) ui.postDelayed(demo::start, 1000);
        renderer = new VizRenderer(library, settings, library.textureSearchPath(this), this,
                prefs.getString("category", PresetLibrary.ALL),
                testPreset != null ? testPreset : prefs.getString("preset", null),
                testPreset != null || prefs.getBoolean("locked", false));
        renderer.pacing = getIntent().getBooleanExtra("pace", true);  // test hook: --ez pace false
        renderer.innerHeight = getIntent().getIntExtra("inner", 0);    // test hook: --ei inner 480
        // Auto picture may climb to the screen's own height (2160 on a 4K TV).
        android.view.Display.Mode mode = getWindowManager().getDefaultDisplay().getMode();
        renderer.screenHeight = Math.min(mode.getPhysicalWidth(), mode.getPhysicalHeight()) > 0
                ? Math.min(2160, Math.min(mode.getPhysicalWidth(), mode.getPhysicalHeight())) : 1080;
        Native.setSharpen(getIntent().getBooleanExtra("sharpen", false)); // test hook: --ez sharpen true
        menu = new MenuPanel(this, settings, library, this);
        menu.setPanels(new FindPanel(this, settings, library, lists, thumbDir(), this),
                new ZeroPanel(this, settings, library, lists, this));
        thumbs = new ThumbGrabber(thumbDir(), io);

        glView = new GLSurfaceView(this);
        glView.setFocusable(true);
        glView.setFocusableInTouchMode(true);
        glView.setEGLContextClientVersion(3);
        glView.setPreserveEGLContextOnPause(true);
        // Rendered below screen size and scaled up by the display hardware: MilkDrop is soft anyway,
        // and slow TV GPUs keep a smooth frame rate. Menu > Picture: Auto (learned per preset) or 480p to 1080p.
        // Auto: projectM draws at a variable height inside a 720p (Smooth) or 1080p (Sharp) surface.
        int h = !settings.isAuto() ? settings.renderHeight() : settings.renderHeight() == Settings.AUTO_SHARP ? 1080 : 720;
        glView.getHolder().setFixedSize(Settings.widthFor(h), h);
        glView.setRenderer(new CountingRenderer(renderer));
        glView.setOnClickListener(v -> glView.queueEvent(() -> renderer.step(1, true, true)));
        glView.setOnLongClickListener(v -> {
            openMenu();
            return true;
        });
        ViewGroup stage = findViewById(R.id.stage);
        stage.addView(glView, 0, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        glView.requestFocus();
        displayChanged();
        if (isResumedNow) startAudio();
    }

    /** The renderer, plus a frame count for the live frame rate. Counting here leaves VizRenderer alone. */
    private final class CountingRenderer implements GLSurfaceView.Renderer {
        private final VizRenderer inner;

        CountingRenderer(VizRenderer inner) {
            this.inner = inner;
        }

        @Override
        public void onSurfaceCreated(javax.microedition.khronos.opengles.GL10 gl,
                                     javax.microedition.khronos.egl.EGLConfig config) {
            thumbs.contextLost();
            inner.onSurfaceCreated(gl, config);
        }

        @Override
        public void onSurfaceChanged(javax.microedition.khronos.opengles.GL10 gl, int width, int height) {
            pictureHeight = height;
            fpsMeter.reset();
            thumbs.surface(width, height);
            inner.onSurfaceChanged(gl, width, height);
        }

        @Override
        public void onDrawFrame(javax.microedition.khronos.opengles.GL10 gl) {
            inner.onDrawFrame(gl);
            long now = android.os.SystemClock.uptimeMillis();
            fpsMeter.frame(now);
            // Find's preview: a small picture of each preset once it has played 10 s (after the frame is drawn,
            // before it is swapped). Does nothing on other frames.
            thumbs.frame(inner.currentPreset(), now);
        }
    }

    /** OK: the bar. */
    private void openMenu() {
        openMenu(false);
    }

    /** The bar, with the 0-list above it when `zero`. */
    private void openMenu(boolean zero) {
        // The tag would repeat what the bar shows.
        overlay.animate().cancel();
        ui.removeCallbacks(hideOverlay);
        overlay.setVisibility(View.GONE);
        // Focus must stay in the menu: the full-screen GL view would otherwise take it from the edges.
        glView.setFocusable(false);
        if (zero) menu.openZero();
        else if (!menu.isOpen()) menu.open();
        watchCovered(true);
        updateCovered();
        ui.removeCallbacks(tick);
        ui.post(tick);
    }

    private void closeMenu() {
        menu.close();
        watchCovered(false);
        updateCovered();
        glView.setFocusable(true);
        glView.requestFocus();
    }

    private boolean isResumedNow;

    @Override
    protected void onResume() {
        super.onResume();
        isResumedNow = true;
        hideSystemBars();
        if (glView != null) glView.onResume();
        startAudio();
        File p = shownPreset;
        if (p != null && history != null) watch.show(p.getName(), android.os.SystemClock.uptimeMillis());
    }

    @Override
    protected void onPause() {
        isResumedNow = false;
        if (demo.isOn()) demo.stop();
        if (history != null) {
            record(watch.end(android.os.SystemClock.uptimeMillis()));
            saveHistory();
        }
        stopAudio();
        if (glView != null) glView.onPause();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        // No projectM teardown here: after recreate() the old GL thread could run it after the new screen has
        // started and kill the new instance. Native.create() frees any previous instance instead.
        super.onDestroy();
    }

    // --- audio ---

    private void startAudio() {
        if (audio != null || renderer == null) return;
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return;
        try {
            // Session 0 = the whole output mix. Needs RECORD_AUDIO; the audio itself is never stored or sent.
            audio = new Visualizer(0);
            audio.setEnabled(false);
            audio.setCaptureSize(Visualizer.getCaptureSizeRange()[1]);
            // As played, not normalized: normalizing scales every 21 ms capture to full level, which flattened the
            // loudness swings projectM finds beats in (measured: swing cv 0.18-0.25 normalized, 0.33-0.66 as
            // played). Native.addPcm evens out the level slowly instead.
            audio.setScalingMode(Visualizer.SCALING_MODE_AS_PLAYED);
            audio.setEnabled(true);
            renderer.audio = audio;
        } catch (RuntimeException e) {
            Log.e(TAG, "Visualizer(0) failed", e);
            audio = null;
            onError("This device does not share its audio with visualizers.");
        }
    }

    private void stopAudio() {
        if (renderer != null) renderer.audio = null;
        if (audio != null) {
            audio.release();
            audio = null;
        }
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        if (code != REQ_AUDIO) return;
        if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) {
            startAudio();
        } else {
            onError("Without audio access the visuals cannot follow the music.");
        }
    }

    // --- remote ---

    /**
     * Keys are taken here, before any view: the full-screen GL view is clickable (tap = next on touch screens)
     * and would otherwise eat OK as a click.
     */
    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (renderer == null || menu == null) return super.dispatchKeyEvent(event);
        int keyCode = event.getKeyCode();
        if (menu.isOpen()) {
            menu.keyPressed();  // the idle close waits from the last key
            if (keyCode == KeyEvent.KEYCODE_BACK || keyCode == KeyEvent.KEYCODE_MENU) {
                if (event.getAction() == KeyEvent.ACTION_UP && (keyCode == KeyEvent.KEYCODE_MENU || !menu.back())) {
                    closeMenu();
                }
                return true;
            }
            // Previous and next are not on the bar: the remote's media keys still do them.
            if (keyCode == KeyEvent.KEYCODE_MEDIA_FAST_FORWARD || keyCode == KeyEvent.KEYCODE_MEDIA_REWIND) {
                if (event.getAction() == KeyEvent.ACTION_DOWN) handleKey(keyCode);
                return true;
            }
            if (menu.handleKey(event)) return true;
            return super.dispatchKeyEvent(event);  // focus moves between the bar and the cards
        }
        if (event.getAction() == KeyEvent.ACTION_DOWN && handleKey(keyCode)) return true;
        if (event.getAction() == KeyEvent.ACTION_UP && isOurKey(keyCode)) return true;
        return super.dispatchKeyEvent(event);
    }

    private static boolean isOurKey(int k) {
        switch (k) {
            case KeyEvent.KEYCODE_DPAD_RIGHT: case KeyEvent.KEYCODE_MEDIA_FAST_FORWARD:
            case KeyEvent.KEYCODE_DPAD_LEFT: case KeyEvent.KEYCODE_MEDIA_REWIND:
            case KeyEvent.KEYCODE_DPAD_UP: case KeyEvent.KEYCODE_CHANNEL_UP:
            case KeyEvent.KEYCODE_DPAD_DOWN: case KeyEvent.KEYCODE_CHANNEL_DOWN:
            case KeyEvent.KEYCODE_DPAD_CENTER: case KeyEvent.KEYCODE_ENTER: case KeyEvent.KEYCODE_NUMPAD_ENTER:
            case KeyEvent.KEYCODE_MENU: case KeyEvent.KEYCODE_INFO:
                return true;
            case KeyEvent.KEYCODE_0: case KeyEvent.KEYCODE_1: case KeyEvent.KEYCODE_2: case KeyEvent.KEYCODE_3:
            case KeyEvent.KEYCODE_4: case KeyEvent.KEYCODE_5: case KeyEvent.KEYCODE_6: case KeyEvent.KEYCODE_7:
            case KeyEvent.KEYCODE_8: case KeyEvent.KEYCODE_9:
                return true;
            default:
                return false;
        }
    }

    /** A number key: add the digit, show the number and the preset it leads to, play it after a short pause. */
    private void typeDigit(int digit) {
        int n = typed.add(digit);
        File f = library.byNumber(n);
        title.setText(typed.shown());
        subtitle.setText(f != null ? PresetLibrary.displayName(f)
                : library.numberNeedsFullPack(n) ? "In the full pack: menu > Get all presets" : "No preset " + n);
        hint.setVisibility(View.GONE);
        overlay.animate().cancel();
        overlay.setVisibility(View.VISIBLE);
        overlay.setAlpha(1f);
        ui.removeCallbacks(hideOverlay);
        ui.removeCallbacks(playTyped);
        ui.postDelayed(playTyped, NUMBER_WAIT_MS);
        updateCovered();
    }

    private void playTyped() {
        ui.removeCallbacks(playTyped);
        int n = typed.take();
        if (n < 0) return;
        File f = library.byNumber(n);
        if (f != null) {
            glView.queueEvent(() -> renderer.play(f));
        } else {
            ui.postDelayed(hideOverlay, 1200);
        }
    }

    private boolean handleKey(int keyCode) {
        boolean ok = keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER
                || keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER;
        if (shuffleAsk.key(ok, android.os.SystemClock.uptimeMillis())) {
            shuffleThisSet();
            return true;
        }
        // 0 first opens the 0-list (favourites 01..., playlists 001...); a 0 inside a number (10, 205) stays a digit.
        if (keyCode == KeyEvent.KEYCODE_0 && !typed.isActive()) {
            openMenu(true);
            return true;
        }
        if (keyCode >= KeyEvent.KEYCODE_0 && keyCode <= KeyEvent.KEYCODE_9) {
            typeDigit(keyCode - KeyEvent.KEYCODE_0);
            return true;
        }
        if (typed.isActive() && (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER
                || keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER)) {
            playTyped();  // OK plays the typed number now
            return true;
        }
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_RIGHT:
            case KeyEvent.KEYCODE_MEDIA_FAST_FORWARD:
                glView.queueEvent(() -> renderer.step(1, true, true));
                return true;
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_MEDIA_REWIND:
                glView.queueEvent(() -> renderer.step(-1, true, true));
                return true;
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_CHANNEL_UP:
                picked = true;
                glView.queueEvent(() -> renderer.changeCategory(1));
                return true;
            case KeyEvent.KEYCODE_DPAD_DOWN:
            case KeyEvent.KEYCODE_CHANNEL_DOWN:
                picked = true;
                glView.queueEvent(() -> renderer.changeCategory(-1));
                return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
            case KeyEvent.KEYCODE_MENU:
                openMenu();
                return true;
            case KeyEvent.KEYCODE_INFO:
                showOverlay(true);
                return true;
            default:
                return false;
        }
    }

    /** OK on "shuffle this one only": This set from now on, and play from the chosen style or list. */
    private void shuffleThisSet() {
        settings.setShuffle(Settings.SHUFFLE_SET);
        glView.queueEvent(renderer::shuffleThisSet);
    }

    // --- overlay ---

    @Override
    public void onPresetShown(String category, File p, boolean locked, boolean userAction) {
        shownPreset = p;
        shownLocked = locked;
        // A test run (adb: --es preset) must not leave its held preset behind for the next normal start.
        if (!testRun) prefs.edit()
                .putString("category", category)
                .putString("preset", p == null ? null : p.getPath())
                .putBoolean("locked", locked)
                .apply();
        String preset = p == null ? "" : library.title(p);
        ui.post(() -> {
            boolean changed = p != null && !p.equals(taggedPreset);
            if (changed || p == null) shownAt = android.os.SystemClock.uptimeMillis();
            if (changed && isResumedNow) record(watch.show(p.getName(), android.os.SystemClock.uptimeMillis()));
            shownCategory = category;
            menu.refresh();
            menu.tick();
            long now = android.os.SystemClock.uptimeMillis();
            if (userAction && picked) {
                picked = false;
                shuffleAsk.chosen(settings.shuffle(), category, now);
            }
            if (typed.isActive()) return;  // the tag shows the number being typed
            boolean asking = shuffleAsk.isAsking(now) && !menu.isOpen();
            title.setText(preset);
            subtitle.setText(asking ? ShuffleAsk.LINE : p == null ? category : BarText.styleLine(library.styleOf(p),
                    library.subStyleOf(p), category, locked, settings.shuffle()));
            // On every change, automatic ones too ("Show what's playing"); otherwise only on key presses.
            if (!menu.isOpen() && (userAction || firstOverlay || (changed && settings.showPlaying()))) {
                showOverlay(userAction && !asking);
            }
            taggedPreset = p;
        });
    }

    private boolean firstOverlay = true;

    private void showOverlay(boolean withHint) {
        // The key hint shows on launch and on key presses, so new users learn the remote.
        hint.setVisibility(withHint || firstOverlay ? View.VISIBLE : View.GONE);
        firstOverlay = false;
        overlay.animate().cancel();
        overlay.setVisibility(View.VISIBLE);
        overlay.setAlpha(1f);
        title.setSelected(true);  // a long name scrolls while the tag is up
        ui.removeCallbacks(hideOverlay);
        ui.postDelayed(hideOverlay, OVERLAY_MS);
        updateCovered();
    }

    /** Tells Auto picture whether anything is drawn over the visuals (see VizRenderer.covered). */
    private void updateCovered() {
        if (renderer != null) {
            renderer.covered = overlay.getVisibility() == View.VISIBLE || (menu != null && menu.isOpen());
        }
    }

    @Override
    public void onError(String message) {
        ui.post(() -> {
            title.setText(message);
            subtitle.setText("");
            hint.setVisibility(View.GONE);
            overlay.animate().cancel();
            overlay.setVisibility(View.VISIBLE);
            overlay.setAlpha(1f);
            ui.removeCallbacks(hideOverlay);
        });
    }

    // --- watch history (kept only on this TV) ---

    private WatchHistory history;
    private SmartLists lists;
    private ThumbGrabber thumbs;
    private final WatchTimer watch = new WatchTimer();
    /** Saves history and thumbnails off the UI and GL threads. */
    private final java.util.concurrent.ExecutorService io = java.util.concurrent.Executors.newSingleThreadExecutor();

    private File historyFile() {
        return new File(getFilesDir(), "history.tsv");
    }

    private File thumbDir() {
        return new File(getFilesDir(), "thumbs");
    }

    /** Preset-scan thread. Missing or unreadable: an empty history. */
    private WatchHistory loadHistory() {
        File f = historyFile();
        if (f.isFile()) {
            try (java.io.Reader r = new java.io.InputStreamReader(new java.io.FileInputStream(f),
                    java.nio.charset.StandardCharsets.UTF_8)) {
                WatchHistory h = WatchHistory.read(r);
                h.prune(System.currentTimeMillis());
                return h;
            } catch (java.io.IOException e) {
                Log.w(TAG, "history unreadable", e);
            }
        }
        return new WatchHistory();
    }

    /** assets/featured.txt (the user's picks); null until it ships, and Featured shows "soon". */
    private List<String> loadFeatured() {
        try (java.io.Reader r = new java.io.InputStreamReader(getAssets().open("featured.txt"),
                java.nio.charset.StandardCharsets.UTF_8)) {
            return SmartLists.parseFeatured(r);
        } catch (java.io.IOException e) {
            return null;
        }
    }

    private void watchCovered(boolean on) {
        watch.cover(on, android.os.SystemClock.uptimeMillis());
    }

    /** A showing ended: into the history if it was watched (10 s or more, menu closed). */
    private void record(WatchTimer.Shown s) {
        if (s == null || history == null) return;
        // Saved at once (a small file, on the io thread): an app killed without onPause keeps what it saw.
        if (history.add(s.name, System.currentTimeMillis(), s.seconds)) saveHistory();
    }

    private void saveHistory() {
        WatchHistory h = history;
        if (h == null || !h.isDirty()) return;
        File f = historyFile();
        io.execute(() -> {
            File tmp = new File(f.getPath() + ".tmp");
            try (java.io.Writer w = new java.io.OutputStreamWriter(new java.io.FileOutputStream(tmp),
                    java.nio.charset.StandardCharsets.UTF_8)) {
                h.write(w);
            } catch (java.io.IOException e) {
                Log.w(TAG, "history not saved", e);
                return;
            }
            if (!tmp.renameTo(f)) Log.w(TAG, "history rename failed");
        });
    }

    // --- Find and the 0-list (UI thread) ---

    @Override
    public void playList(String name, List<File> presets, File first) {
        picked = true;
        List<File> copy = new java.util.ArrayList<>(presets);
        glView.queueEvent(() -> renderer.playList(name, copy, first));
    }

    @Override
    public void playCategory(String category, File first) {
        picked = true;
        glView.queueEvent(() -> renderer.playCategory(category, first));
    }

    @Override
    public void dismiss() {
        if (menu.isOpen()) closeMenu();
    }

    // --- MenuPanel.Host (UI thread) ---

    @Override
    public File currentPreset() {
        return shownPreset;
    }

    @Override
    public boolean isLocked() {
        return shownLocked;
    }

    @Override
    public void play(File preset) {
        glView.queueEvent(() -> renderer.play(preset));
    }

    @Override
    protected void onActivityResult(int code, int result, android.content.Intent data) {
        super.onActivityResult(code, result, data);
        if (code == SearchPanel.REQ_SPEECH && menu != null) menu.onSpeech(result, data);
    }

    @Override
    public void step(int delta) {
        glView.queueEvent(() -> renderer.step(delta, true, true));
    }

    @Override
    public String category() {
        return shownCategory;
    }

    @Override
    public long shownAt() {
        return shownAt;
    }

    @Override
    public float fps() {
        return fpsMeter.average();
    }

    @Override
    public int pictureHeight() {
        return pictureHeight;
    }

    @Override
    public void displayChanged() {
        updateCovered();
    }

    @Override
    public void setHold(boolean on) {
        glView.queueEvent(() -> renderer.setLocked(on));
    }

    @Override
    public void hideCurrent() {
        File p = shownPreset;
        if (p == null) return;
        settings.hide(p.getName());
        glView.queueEvent(renderer::applySettings);
    }

    @Override
    public void settingsChanged() {
        glView.queueEvent(renderer::applySettings);
    }

    @Override
    public void resolutionChanged() {
        glView.queueEvent(renderer::applySettings);
    }

    private volatile String packStatus;

    // --- demo songs ---

    private final DemoPlayer demo = new DemoPlayer((index, error) -> {
        if (error != null) showNote(error, "");
        else if (index >= 0) showNote("Demo: " + DemoSongs.title(index), DemoSongs.CREDIT);
        if (menu != null) menu.refresh();
    });

    @Override
    public String demoSong() {
        return demo.isOn() ? (demo.index() >= 0 ? DemoSongs.title(demo.index()) : "Starting…") : null;
    }

    @Override
    public void toggleDemo() {
        if (demo.isOn()) demo.stop();
        else demo.start();
    }

    /** A short note in the overlay, hidden after the usual 4 s. */
    private void showNote(String line1, String line2) {
        title.setText(line1);
        subtitle.setText(line2);
        hint.setVisibility(View.GONE);
        overlay.animate().cancel();
        overlay.setVisibility(View.VISIBLE);
        overlay.setAlpha(1f);
        ui.removeCallbacks(hideOverlay);
        ui.postDelayed(hideOverlay, OVERLAY_MS);
    }

    @Override
    public String packStatus() {
        return packStatus;
    }

    @Override
    public void downloadPack() {
        if (PackDownloader.isRunning()) return;
        packStatus = "Starting…";
        menu.refresh();
        PackDownloader.start(getApplicationContext(), (pct, error) -> ui.post(() -> {
            if (pct >= 0) {
                packStatus = "Downloading " + pct + "%";
            } else if (error != null) {
                packStatus = error;
            } else {
                packStatus = null;
                // New presets on disk: restart so the library rescans them. Settings and hides are kept.
                recreate();
                return;
            }
            menu.refresh();
        }));
    }

    @Override
    public void onPaced(boolean on) {
        ui.post(() -> {
            if (glView == null || on == pacedFrames) return;
            pacedFrames = on;
            if (on) {
                glView.setRenderMode(GLSurfaceView.RENDERMODE_WHEN_DIRTY);
                android.view.Choreographer.getInstance().postFrameCallback(everySecondVsync);
            } else {
                android.view.Choreographer.getInstance().removeFrameCallback(everySecondVsync);
                glView.setRenderMode(GLSurfaceView.RENDERMODE_CONTINUOUSLY);
            }
        });
    }

    /** UI thread: true while frames are drawn on every second vsync (an even 30 fps). */
    private boolean pacedFrames;
    private boolean oddVsync;
    private final android.view.Choreographer.FrameCallback everySecondVsync = new android.view.Choreographer.FrameCallback() {
        @Override
        public void doFrame(long frameTimeNanos) {
            if (!pacedFrames || glView == null) return;
            oddVsync = !oddVsync;
            if (oddVsync) glView.requestRender();
            android.view.Choreographer.getInstance().postFrameCallback(this);
        }
    };

    @Override
    public void onWantHeight(int height) {
        ui.post(() -> {
            glView.getHolder().setFixedSize(Settings.widthFor(height), height);
            menu.refresh();
        });
    }

    /** CPU and RAM, to compare TVs when reading logs (the GPU name is logged when projectM starts). */
    private void logDevice() {
        android.app.ActivityManager.MemoryInfo mem = new android.app.ActivityManager.MemoryInfo();
        ((android.app.ActivityManager) getSystemService(ACTIVITY_SERVICE)).getMemoryInfo(mem);
        String mhz = "?";
        try (java.io.BufferedReader r = new java.io.BufferedReader(new java.io.FileReader(
                "/sys/devices/system/cpu/cpu0/cpufreq/cpuinfo_max_freq"))) {
            mhz = String.valueOf(Long.parseLong(r.readLine().trim()) / 1000);
        } catch (Exception ignored) {
        }
        Log.i(TAG, String.format("device %s: %d cores, %s MHz, RAM %d MB (%d MB free)", android.os.Build.MODEL,
                Runtime.getRuntime().availableProcessors(), mhz, mem.totalMem >> 20, mem.availMem >> 20));
    }

    private void hideSystemBars() {
        if (android.os.Build.VERSION.SDK_INT < 30) {
            getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
            return;
        }
        WindowInsetsController c = getWindow().getInsetsController();
        if (c != null) {
            c.hide(WindowInsets.Type.systemBars());
            c.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
        }
    }
}
