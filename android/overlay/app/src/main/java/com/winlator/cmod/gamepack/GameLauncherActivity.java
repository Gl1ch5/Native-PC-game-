package com.winlator.cmod.gamepack;

import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.winlator.cmod.MainActivity;
import com.winlator.cmod.R;
import com.winlator.cmod.container.Container;
import com.winlator.cmod.container.ContainerManager;

import java.io.File;
import java.util.concurrent.Executors;

/** Home-screen entry point: first-run setup, install progress, and a direct start of the game. */
public class GameLauncherActivity extends AppCompatActivity implements GamePackInstallService.Listener {
    private static final long AUTO_START_DELAY_MS = 1500;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private GamePack pack;
    private GameSettings settings;
    private TextView statusView;
    private TextView percentView;
    private ProgressBar progressBar;
    private Button primaryButton;
    private Button settingsButton;
    private Button cancelButton;
    private boolean launching;
    private final Runnable autoStart = this::launchGame;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setContentView(R.layout.gamepack_launcher);
        hideSystemUi();

        settings = new GameSettings(this);
        statusView = findViewById(R.id.GPStatus);
        percentView = findViewById(R.id.GPPercent);
        progressBar = findViewById(R.id.GPProgress);
        primaryButton = findViewById(R.id.GPPrimaryButton);
        settingsButton = findViewById(R.id.GPSettingsButton);
        cancelButton = findViewById(R.id.GPCancelButton);
        TextView titleView = findViewById(R.id.GPTitle);

        try {
            pack = GamePack.load(this);
        }
        catch (Exception e) {
            statusView.setText(getString(R.string.gamepack_error_config, e.getMessage()));
            primaryButton.setVisibility(View.GONE);
            return;
        }
        titleView.setText(pack.title);
        ((TextView)findViewById(R.id.GPNotice)).setText(pack.notice);

        // Hidden way to reach the full Winlator UI for troubleshooting.
        titleView.setOnLongClickListener(v -> {
            handler.removeCallbacks(autoStart);
            startActivity(new Intent(this, MainActivity.class));
            return true;
        });

        settingsButton.setOnClickListener(v -> {
            handler.removeCallbacks(autoStart);
            GameSetupDialog.show(this, false, this::refresh);
        });
        cancelButton.setOnClickListener(v -> GamePackInstallService.cancel(this));

        if (!settings.isConfigured()) {
            GameSetupDialog.show(this, true, () -> {
                refresh();
                if (!isInstalled() && !GamePackInstallService.isRunning()) GamePackInstallService.start(this);
            });
        }
        else if (!isInstalled() && !GamePackInstallService.isRunning()
                && GamePackInstallService.getState().stage != GamePackInstallService.Stage.FAILED) {
            GamePackInstallService.start(this);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        hideSystemUi();
        launching = false;
        if (pack == null) return;
        GamePackInstallService.addListener(this);
        refresh();
        if (settings.isConfigured() && isInstalled() && !GamePackInstallService.isRunning()) {
            statusView.setText(R.string.gamepack_starting);
            handler.postDelayed(autoStart, AUTO_START_DELAY_MS);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        handler.removeCallbacks(autoStart);
        GamePackInstallService.removeListener(this);
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) hideSystemUi();
    }

    private void hideSystemUi() {
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                | View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN);
    }

    private boolean isInstalled() {
        String exe = settings.getInstalledExe();
        if (exe.isEmpty() || settings.getInstalledVersion() < pack.packageVersion) return false;
        Container container = new GamePackSetup(this, pack, settings).findContainer(new ContainerManager(this));
        if (container == null) return false;
        File exeFile = new File(GamePackSetup.getDriveC(container), exe.substring(2).replace('\\', '/'));
        return exeFile.isFile();
    }

    private void refresh() {
        if (pack == null) return;
        boolean running = GamePackInstallService.isRunning();
        boolean installed = !running && isInstalled();

        cancelButton.setVisibility(running ? View.VISIBLE : View.GONE);
        progressBar.setVisibility(running ? View.VISIBLE : View.INVISIBLE);
        percentView.setVisibility(running ? View.VISIBLE : View.INVISIBLE);
        primaryButton.setVisibility(running ? View.GONE : View.VISIBLE);
        settingsButton.setEnabled(!running);

        if (installed) {
            primaryButton.setText(R.string.gamepack_play);
            primaryButton.setOnClickListener(v -> launchGame());
            if (!launching) statusView.setText(R.string.gamepack_ready);
        }
        else if (!running) {
            GamePackInstallService.State state = GamePackInstallService.getState();
            primaryButton.setText(state.stage == GamePackInstallService.Stage.FAILED ? R.string.gamepack_retry : R.string.gamepack_install);
            primaryButton.setOnClickListener(v -> {
                GamePackInstallService.start(this);
                refresh();
            });
            statusView.setText(state.stage == GamePackInstallService.Stage.FAILED
                    ? getString(R.string.gamepack_error_prefix, state.detail) : getString(R.string.gamepack_not_installed));
        }
    }

    @Override
    public void onStateChanged(GamePackInstallService.State state) {
        if (pack == null) return;
        switch (state.stage) {
            case IDLE:
                return;
            case DONE:
            case FAILED:
                refresh();
                return;
            default:
                refresh();
                statusView.setText(state.detail);
                if (state.progress < 0) {
                    progressBar.setIndeterminate(true);
                    percentView.setText("");
                }
                else {
                    progressBar.setIndeterminate(false);
                    progressBar.setProgress(state.progress);
                    percentView.setText(state.progress / 10 + "%");
                }
        }
    }

    private void launchGame() {
        if (launching || GamePackInstallService.isRunning()) return;
        launching = true;
        handler.removeCallbacks(autoStart);
        statusView.setText(R.string.gamepack_starting);
        primaryButton.setEnabled(false);

        Executors.newSingleThreadExecutor().execute(() -> {
            GamePackSetup setup = new GamePackSetup(this, pack, settings);
            Container container = setup.findContainer(new ContainerManager(this));
            if (container == null) {
                runOnUiThread(() -> {
                    launching = false;
                    primaryButton.setEnabled(true);
                    settings.clearInstalled();
                    refresh();
                });
                return;
            }
            File shortcut = setup.writeShortcut(container, settings.getInstalledExe());
            Intent intent = setup.buildLaunchIntent(container, shortcut);
            runOnUiThread(() -> {
                primaryButton.setEnabled(true);
                startActivity(intent);
                overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);
                finish();
            });
        });
    }
}
