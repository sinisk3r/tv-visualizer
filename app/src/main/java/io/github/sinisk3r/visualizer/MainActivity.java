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
    private final Runnable hideOverlay = () -> overlay.animate().alpha(0f).setDuration(600).start();

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setContentView(R.layout.activity_main);
        glView = findViewById(R.id.gl);
        overlay = findViewById(R.id.overlay);
        title = findViewById(R.id.title);
        subtitle = findViewById(R.id.subtitle);
        hint = findViewById(R.id.hint);
        prefs = getSharedPreferences("state", MODE_PRIVATE);
        settings = new Settings(this);

        PresetLibrary library;
        try {
            library = new PresetLibrary(this);
        } catch (Exception e) {
            Log.e(TAG, "preset unpack failed", e);
            onError("Could not unpack presets: " + e.getMessage());
            return;
        }
        renderer = new VizRenderer(library, settings, library.textureSearchPath(this), this,
                prefs.getString("category", PresetLibrary.ALL),
                prefs.getString("preset", null),
                prefs.getBoolean("locked", false));

        glView.setEGLContextClientVersion(3);
        glView.setPreserveEGLContextOnPause(true);
        // Rendered below screen size and scaled up by the display hardware: MilkDrop is soft anyway,
        // and slow TV GPUs keep a smooth frame rate. Menu > Picture: Auto (learned per preset) or 480p to 1080p.
        int h = settings.isAuto() ? 720 : settings.renderHeight();  // Auto: each preset then asks for its own
        glView.getHolder().setFixedSize(Settings.widthFor(h), h);
        glView.setRenderer(renderer);
        glView.setOnClickListener(v -> glView.queueEvent(() -> renderer.step(1, false, true)));
        glView.setOnLongClickListener(v -> {
            menu.open();
            return true;
        });
        menu = new MenuPanel(this, settings, library, this);

        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_AUDIO);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        hideSystemBars();
        if (glView != null) glView.onResume();
        startAudio();
    }

    @Override
    protected void onPause() {
        stopAudio();
        if (glView != null) glView.onPause();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        if (glView != null && renderer != null) glView.queueEvent(renderer::release);
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
            audio.setScalingMode(Visualizer.SCALING_MODE_NORMALIZED);
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
                if (event.getAction() == KeyEvent.ACTION_UP) {
                    menu.close();
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
                glView.queueEvent(() -> renderer.step(1, false, true));
                return true;
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_MEDIA_REWIND:
                glView.queueEvent(() -> renderer.step(-1, false, true));
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
        overlay.setAlpha(1f);
        ui.removeCallbacks(hideOverlay);
        ui.postDelayed(hideOverlay, OVERLAY_MS);
    }

    @Override
    public void onError(String message) {
        ui.post(() -> {
            title.setText(message);
            subtitle.setText("");
            hint.setVisibility(View.GONE);
            overlay.animate().cancel();
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

    @Override
    public float level() {
        return renderer.level;
    }

    @Override
    public void onWantHeight(int height) {
        ui.post(() -> {
            glView.getHolder().setFixedSize(Settings.widthFor(height), height);
            menu.refresh();
        });
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
