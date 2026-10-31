package com.winlator.cmod.gamepack;

import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.view.Surface;

import com.winlator.cmod.XServerDisplayActivity;
import com.winlator.cmod.inputcontrols.ControlsProfile;
import com.winlator.cmod.inputcontrols.GamepadState;
import com.winlator.cmod.winhandler.WinHandler;

/**
 * Steering by tilting the phone like a wheel. Uses the gravity vector (absolute roll angle),
 * unlike Winlator's gyro mode which maps rotation speed and suits camera control instead.
 * The angle is written to the left stick X of the virtual gamepad profile.
 */
public class TiltSteering implements SensorEventListener {
    private static final float DEADZONE_DEGREES = 1.5f;
    private static final float SMOOTHING = 0.35f;

    private final XServerDisplayActivity activity;
    private final GameSettings settings;
    private final SensorManager sensorManager;
    private final Sensor sensor;
    private boolean running;
    private float smoothedAngle;
    private float lastSent = Float.NaN;
    private volatile float currentRoll;

    public TiltSteering(XServerDisplayActivity activity, GameSettings settings) {
        this.activity = activity;
        this.settings = settings;
        sensorManager = (SensorManager)activity.getSystemService(Context.SENSOR_SERVICE);
        Sensor gravity = sensorManager.getDefaultSensor(Sensor.TYPE_GRAVITY);
        sensor = gravity != null ? gravity : sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
    }

    public boolean isAvailable() {
        return sensor != null;
    }

    public void start() {
        if (running || sensor == null) return;
        running = true;
        sensorManager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_GAME);
    }

    public void stop() {
        if (!running) return;
        running = false;
        sensorManager.unregisterListener(this);
        writeSteering(0f);
    }

    /** Uses the current phone position as the wheel centre. */
    public void calibrate() {
        settings.setTiltCenter(currentRoll);
        smoothedAngle = 0f;
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        float gx = event.values[0];
        float gy = event.values[1];
        float gz = event.values[2];
        // The game screen is landscape-only, so the wheel axis is the device's Y axis.
        // In ROTATION_90 the top edge of the phone is on the left: turning right lifts it, so gy grows.
        int rotation = activity.getWindowManager().getDefaultDisplay().getRotation();
        float axis = rotation == Surface.ROTATION_270 ? -gy : gy;
        float roll = (float)Math.toDegrees(Math.atan2(axis, Math.hypot(gx, gz)));
        currentRoll = roll;

        float angle = roll - settings.getTiltCenter();
        smoothedAngle += (angle - smoothedAngle) * SMOOTHING;

        float value = 0f;
        if (Math.abs(smoothedAngle) > DEADZONE_DEGREES) {
            float sign = Math.signum(smoothedAngle);
            value = sign * (Math.abs(smoothedAngle) - DEADZONE_DEGREES) / (settings.getTiltMaxAngle() - DEADZONE_DEGREES);
            value = Math.max(-1f, Math.min(1f, value));
        }
        if (Float.isNaN(lastSent) || Math.abs(value - lastSent) > 0.004f) writeSteering(value);
    }

    private void writeSteering(float value) {
        lastSent = value;
        ControlsProfile profile = activity.getInputControlsView().getProfile();
        WinHandler winHandler = activity.getWinHandler();
        if (profile == null || !profile.isVirtualGamepad() || winHandler == null) return;
        GamepadState state = profile.getGamepadState();
        state.thumbLX = value;
        winHandler.sendGamepadState();
        winHandler.pokeSharedMemory();
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {}
}
