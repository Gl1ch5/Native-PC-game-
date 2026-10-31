package com.winlator.cmod.gamepack;

import android.app.Dialog;
import android.view.LayoutInflater;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.ArrayAdapter;
import android.widget.CompoundButton;
import android.widget.RadioGroup;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import com.winlator.cmod.R;
import com.winlator.cmod.contents.AdrenotoolsManager;

import java.util.ArrayList;
import java.util.List;

/** First-run / settings screen: controls layout, graphics preset, FPS counter. */
public class GameSetupDialog {
    private static final int TILT_MIN_ANGLE = 12;
    private static final int TILT_MAX_ANGLE = 50;

    public static int controlsToId(GameSettings.Controls controls) {
        switch (controls) {
            case TOUCH: return R.id.GPControlsTouch;
            case TILT: return R.id.GPControlsTilt;
            case KEYBOARD: return R.id.GPControlsKeyboard;
            default: return R.id.GPControlsGamepad;
        }
    }

    public static GameSettings.Controls idToControls(int id) {
        if (id == R.id.GPControlsTouch) return GameSettings.Controls.TOUCH;
        if (id == R.id.GPControlsTilt) return GameSettings.Controls.TILT;
        if (id == R.id.GPControlsKeyboard) return GameSettings.Controls.KEYBOARD;
        return GameSettings.Controls.GAMEPAD;
    }

    private static int qualityToId(GameSettings.Quality quality) {
        switch (quality) {
            case ECO: return R.id.GPQualityEco;
            case HIGH: return R.id.GPQualityHigh;
            default: return R.id.GPQualityBalanced;
        }
    }

    private static GameSettings.Quality idToQuality(int id) {
        if (id == R.id.GPQualityEco) return GameSettings.Quality.ECO;
        if (id == R.id.GPQualityHigh) return GameSettings.Quality.HIGH;
        return GameSettings.Quality.BALANCED;
    }

    /** Seek bar to the right = more sensitive = smaller angle for full lock. */
    public static void bindTiltSensitivity(SeekBar seekBar, GameSettings settings) {
        seekBar.setMax(TILT_MAX_ANGLE - TILT_MIN_ANGLE);
        seekBar.setProgress(Math.round(TILT_MAX_ANGLE - settings.getTiltMaxAngle()));
        seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                if (fromUser) settings.setTiltMaxAngle(TILT_MAX_ANGLE - progress);
            }

            @Override
            public void onStartTrackingTouch(SeekBar bar) {}

            @Override
            public void onStopTrackingTouch(SeekBar bar) {}
        });
    }

    /** Fills the driver list: system driver first, then drivers imported from archives. */
    private static List<String> bindDrivers(GameLauncherActivity activity, Spinner spinner, String selected) {
        List<String> ids = new ArrayList<>();
        List<String> labels = new ArrayList<>();
        ids.add(GameSettings.SYSTEM_DRIVER);
        labels.add(activity.getString(R.string.gamepack_gpu_driver_system));
        AdrenotoolsManager manager = new AdrenotoolsManager(activity);
        for (String id : manager.enumarateInstalledDrivers()) {
            ids.add(id);
            String version = manager.getDriverVersion(id);
            labels.add(version.isEmpty() ? id : id + " (" + version + ")");
        }
        ArrayAdapter<String> adapter = new ArrayAdapter<>(activity, android.R.layout.simple_spinner_item, labels);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinner.setAdapter(adapter);
        spinner.setSelection(Math.max(0, ids.indexOf(selected)));
        return ids;
    }

    public static void show(GameLauncherActivity activity, boolean firstRun, Runnable onDone) {
        GameSettings settings = new GameSettings(activity);
        View view = LayoutInflater.from(activity).inflate(R.layout.gamepack_setup, null);
        Dialog dialog = new Dialog(activity, R.style.GamePackDialog);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setContentView(view);
        dialog.setCancelable(!firstRun);

        ((TextView)view.findViewById(R.id.GPSetupTitle)).setText(firstRun ? R.string.gamepack_setup_title_first : R.string.gamepack_settings);

        RadioGroup controlsGroup = view.findViewById(R.id.GPControlsGroup);
        RadioGroup qualityGroup = view.findViewById(R.id.GPQualityGroup);
        CompoundButton showFps = view.findViewById(R.id.GPShowFps);
        View tiltSection = view.findViewById(R.id.GPTiltSection);
        SeekBar sensitivity = view.findViewById(R.id.GPTiltSensitivity);

        controlsGroup.check(controlsToId(settings.getControls()));
        qualityGroup.check(qualityToId(settings.getQuality()));
        showFps.setChecked(settings.isShowFps());
        tiltSection.setVisibility(settings.getControls() == GameSettings.Controls.TILT ? View.VISIBLE : View.GONE);
        view.findViewById(R.id.GPTiltCalibrate).setVisibility(View.GONE); // calibrated in-game from the pause menu
        bindTiltSensitivity(sensitivity, settings);

        Spinner driverSpinner = view.findViewById(R.id.GPDriverSpinner);
        final List<String>[] driverIds = new List[]{bindDrivers(activity, driverSpinner, settings.getGpuDriver())};
        view.findViewById(R.id.GPDriverImport).setOnClickListener(v -> activity.pickDriverArchive(driverId -> {
            if (driverId == null || driverId.isEmpty()) {
                Toast.makeText(activity, R.string.gamepack_gpu_driver_failed, Toast.LENGTH_LONG).show();
                return;
            }
            Toast.makeText(activity, activity.getString(R.string.gamepack_gpu_driver_installed, driverId), Toast.LENGTH_SHORT).show();
            driverIds[0] = bindDrivers(activity, driverSpinner, driverId);
        }));

        controlsGroup.setOnCheckedChangeListener((group, checkedId) ->
                tiltSection.setVisibility(idToControls(checkedId) == GameSettings.Controls.TILT ? View.VISIBLE : View.GONE));

        view.findViewById(R.id.GPSetupDone).setOnClickListener(v -> {
            settings.save(idToControls(controlsGroup.getCheckedRadioButtonId()),
                    idToQuality(qualityGroup.getCheckedRadioButtonId()), showFps.isChecked());
            int driverIndex = driverSpinner.getSelectedItemPosition();
            settings.setGpuDriver(driverIndex > 0 && driverIndex < driverIds[0].size() ? driverIds[0].get(driverIndex) : GameSettings.SYSTEM_DRIVER);
            dialog.dismiss();
            if (onDone != null) onDone.run();
        });

        dialog.show();
        Window window = dialog.getWindow();
        if (window != null) window.setLayout(WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.MATCH_PARENT);
    }
}
