package com.winlator.cmod.gamepack;

import android.app.Activity;
import android.app.Dialog;
import android.view.LayoutInflater;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.CompoundButton;
import android.widget.RadioGroup;
import android.widget.SeekBar;
import android.widget.TextView;

import com.winlator.cmod.R;

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

    public static void show(Activity activity, boolean firstRun, Runnable onDone) {
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

        controlsGroup.setOnCheckedChangeListener((group, checkedId) ->
                tiltSection.setVisibility(idToControls(checkedId) == GameSettings.Controls.TILT ? View.VISIBLE : View.GONE));

        view.findViewById(R.id.GPSetupDone).setOnClickListener(v -> {
            settings.save(idToControls(controlsGroup.getCheckedRadioButtonId()),
                    idToQuality(qualityGroup.getCheckedRadioButtonId()), showFps.isChecked());
            dialog.dismiss();
            if (onDone != null) onDone.run();
        });

        dialog.show();
        Window window = dialog.getWindow();
        if (window != null) window.setLayout(WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.MATCH_PARENT);
    }
}
