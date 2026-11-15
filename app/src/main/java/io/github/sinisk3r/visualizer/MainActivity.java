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

/**
 * Full-screen MilkDrop visuals that follow whatever the device is playing (Spotify, YouTube, ...).
 * Remote: Left/Right = previous/next preset, Up/Down = style (category), OK = menu (MenuPanel).
 */
public class MainActivity extends Activity implements VizRenderer.Listener, MenuPanel.Host {
    private static final String TAG = "Visualizer";
    private static final int REQ_AUDIO = 1;
    private static final long OVERLAY_MS = 4000;

    private GLSurfaceView glView;
    private VizRenderer renderer;
    private TextView title, subtitle, hint;
    private View overlay;
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
        settings.logLearned();
        logDevice();

        // Finding ~10,000 preset files takes 1-2 s on TV storage: do it off the main thread, then start drawing.
        new Thread(() -> {
            try {
                PresetLibrary library = new PresetLibrary(this);
                ui.post(() -> startVisuals(library));
            } catch (Exception e) {
                Log.e(TAG, "preset unpack failed", e);
                onError("Could not unpack presets: " + e.getMessage());
            }
        }, "preset-scan").start();

        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_AUDIO);
        }
    }

    private void startVisuals(PresetLibrary library) {
        if (isDestroyed()) return;
        // Test hook (adb): start on one preset, held, at a fixed picture height, without touching saved settings:
        // am start -n .../.MainActivity --es preset <path on the TV> --ei height 480
        String testPreset = getIntent().getStringExtra("preset");
        int testHeight = getIntent().getIntExtra("height", 0);
        if (testHeight > 0) settings.overrideHeight(testHeight);
        renderer = new VizRenderer(library, settings, library.textureSearchPath(this), this,
                prefs.getString("category", PresetLibrary.ALL),
                testPreset != null ? testPreset : prefs.getString("preset", null),
                testPreset != null || prefs.getBoolean("locked", false));
        menu = new MenuPanel(this, settings, library, this);

        glView = new GLSurfaceView(this);
        glView.setFocusable(true);
        glView.setFocusableInTouchMode(true);
        glView.setEGLContextClientVersion(3);
        glView.setPreserveEGLContextOnPause(true);
        // Rendered below screen size and scaled up by the display hardware: MilkDrop is soft anyway,
        // and slow TV GPUs keep a smooth frame rate. Menu > Picture: Auto (learned per preset) or 480p to 1080p.
        int h = settings.isAuto() ? 720 : settings.renderHeight();  // Auto: each preset then asks for its own
        glView.getHolder().setFixedSize(Settings.widthFor(h), h);
        glView.setRenderer(renderer);
        glView.setOnClickListener(v -> glView.queueEvent(() -> renderer.step(1, true, true)));
        glView.setOnLongClickListener(v -> {
            menu.open();
            updateCovered();
            return true;
        });
        ViewGroup stage = findViewById(R.id.stage);
        stage.addView(glView, 0, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        glView.requestFocus();
        if (isResumedNow) startAudio();
    }

    private boolean isResumedNow;

    @Override
    protected void onResume() {
        super.onResume();
        isResumedNow = true;
        hideSystemBars();
        if (glView != null) glView.onResume();
        startAudio();
    }

    @Override
    protected void onPause() {
        isResumedNow = false;
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
            if (keyCode == KeyEvent.KEYCODE_BACK || keyCode == KeyEvent.KEYCODE_MENU) {
                if (event.getAction() == KeyEvent.ACTION_UP && (keyCode == KeyEvent.KEYCODE_MENU || !menu.back())) {
                    menu.close();
                    updateCovered();
                    glView.requestFocus();
                }
                return true;
            }
            return super.dispatchKeyEvent(event);  // focus moves between menu rows
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
            default:
                return false;
        }
    }

    private boolean handleKey(int keyCode) {
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
                glView.queueEvent(() -> renderer.changeCategory(1));
                return true;
            case KeyEvent.KEYCODE_DPAD_DOWN:
            case KeyEvent.KEYCODE_CHANNEL_DOWN:
                glView.queueEvent(() -> renderer.changeCategory(-1));
                return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
            case KeyEvent.KEYCODE_MENU:
                menu.open();
                updateCovered();
                return true;
            case KeyEvent.KEYCODE_INFO:
                showOverlay(true);
                return true;
            default:
                return false;
        }
    }

    // --- overlay ---

    @Override
    public void onPresetShown(String category, File p, boolean locked, boolean userAction) {
        shownPreset = p;
        shownLocked = locked;
        prefs.edit()
                .putString("category", category)
                .putString("preset", p == null ? null : p.getPath())
                .putBoolean("locked", locked)
                .apply();
        String preset = p == null ? "" : PresetLibrary.displayName(p);
        ui.post(() -> {
            menu.refresh();
            title.setText(preset);
            subtitle.setText(getString(locked ? R.string.subtitle_locked : R.string.subtitle, category));
            if (userAction || firstOverlay) showOverlay(userAction);
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
