package com.winlator.cmod.gamepack;

import android.app.Dialog;
import android.view.LayoutInflater;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.RadioGroup;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import com.winlator.cmod.R;
import com.winlator.cmod.XServerDisplayActivity;
import com.winlator.cmod.core.ProcessHelper;
import com.winlator.cmod.inputcontrols.ControlsProfile;
import com.winlator.cmod.inputcontrols.InputControlsManager;

/**
 * In-game layer for game-pack shortcuts: tilt steering and the pause menu
 * opened with Back (replaces the Winlator drawer).
 */
public class GameOverlay {
    private final XServerDisplayActivity activity;
    private final GameSettings settings;
    private final TiltSteering tiltSteering;
    private Dialog menu;

    public GameOverlay(XServerDisplayActivity activity) {
        this.activity = activity;
        this.settings = new GameSettings(activity);
        this.tiltSteering = new TiltSteering(activity, settings);
        if (settings.getControls() == GameSettings.Controls.TILT) tiltSteering.start();
    }

    public void onResume() {
        if (settings.getControls() == GameSettings.Controls.TILT && (menu == null || !menu.isShowing())) tiltSteering.start();
    }

    public void onPause() {
        tiltSteering.stop();
    }

    public void toggleMenu() {
        if (menu != null && menu.isShowing()) menu.dismiss();
        else showMenu();
    }

    private void showMenu() {
        tiltSteering.stop();
        ProcessHelper.pauseAllWineProcesses();

        View view = LayoutInflater.from(activity).inflate(R.layout.gamepack_menu, null);
        menu = new Dialog(activity, R.style.GamePackDialog);
        menu.requestWindowFeature(Window.FEATURE_NO_TITLE);
        menu.setContentView(view);
        menu.setOnDismissListener(d -> {
            ProcessHelper.resumeAllWineProcesses();
            if (settings.getControls() == GameSettings.Controls.TILT) tiltSteering.start();
        });

        try {
            ((TextView)view.findViewById(R.id.GPMenuTitle)).setText(GamePack.load(activity).title);
        }
        catch (Exception ignored) {}

        RadioGroup controlsGroup = view.findViewById(R.id.GPControlsGroup);
        View tiltSection = view.findViewById(R.id.GPTiltSection);
        controlsGroup.check(GameSetupDialog.controlsToId(settings.getControls()));
        tiltSection.setVisibility(settings.getControls() == GameSettings.Controls.TILT ? View.VISIBLE : View.GONE);
        if (!tiltSteering.isAvailable()) view.findViewById(R.id.GPControlsTilt).setEnabled(false);

        controlsGroup.setOnCheckedChangeListener((group, checkedId) -> {
            GameSettings.Controls controls = GameSetupDialog.idToControls(checkedId);
            settings.setControls(controls);
            applyControls(controls);
            tiltSection.setVisibility(controls == GameSettings.Controls.TILT ? View.VISIBLE : View.GONE);
        });

        SeekBar sensitivity = view.findViewById(R.id.GPTiltSensitivity);
        GameSetupDialog.bindTiltSensitivity(sensitivity, settings);
        view.findViewById(R.id.GPTiltCalibrate).setOnClickListener(v -> {
            tiltSteering.start();
            v.postDelayed(() -> {
                tiltSteering.calibrate();
                tiltSteering.stop();
                Toast.makeText(activity, R.string.gamepack_tilt_calibrated, Toast.LENGTH_SHORT).show();
            }, 300);
        });

        view.findViewById(R.id.GPMenuResume).setOnClickListener(v -> menu.dismiss());
        view.findViewById(R.id.GPMenuExit).setOnClickListener(v -> {
            menu.setOnDismissListener(null);
            menu.dismiss();
            ProcessHelper.resumeAllWineProcesses();
            activity.exitApp();
        });

        menu.show();
        Window window = menu.getWindow();
        if (window != null) {
            window.setLayout(WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT);
            window.setFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE);
            window.getDecorView().setSystemUiVisibility(activity.getWindow().getDecorView().getSystemUiVisibility());
            window.clearFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE);
        }
    }

    /** Switches the on-screen layout immediately, without restarting the game. */
    private void applyControls(GameSettings.Controls controls) {
        int profileId = GamePackSetup.getProfileId(controls);
        if (profileId == 0) {
            activity.hideInputControls();
            return;
        }
        GamePackSetup.installControlProfiles(activity);
        ControlsProfile profile = new InputControlsManager(activity).getProfile(profileId);
        if (profile != null) activity.showInputControls(profile);
    }
}
