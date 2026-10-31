package com.winlator.cmod.gamepack;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.StatFs;

import androidx.core.app.NotificationCompat;

import com.winlator.cmod.R;
import com.winlator.cmod.container.Container;
import com.winlator.cmod.container.ContainerManager;
import com.winlator.cmod.core.FileUtils;
import com.winlator.cmod.xenvironment.ImageFsInstaller;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Foreground service that prepares the system image, downloads and unpacks the game,
 * reporting progress both to the launcher screen and to a notification.
 */
public class GamePackInstallService extends Service {
    public enum Stage { IDLE, SYSTEM, CONTAINER, DOWNLOAD, VERIFY, EXTRACT, DONE, FAILED }

    public static class State {
        public final Stage stage;
        /** 0..1000, or -1 when indeterminate. */
        public final int progress;
        public final String detail;

        State(Stage stage, int progress, String detail) {
            this.stage = stage;
            this.progress = progress;
            this.detail = detail;
        }
    }

    public interface Listener {
        void onStateChanged(State state);
    }

    public static final String ACTION_CANCEL = "com.winlator.cmod.gamepack.CANCEL";
    private static final String CHANNEL_ID = "gamepack_install";
    private static final int NOTIFICATION_ID = 7001;

    private static final List<Listener> listeners = new CopyOnWriteArrayList<>();
    private static volatile State state = new State(Stage.IDLE, 0, "");
    private static volatile boolean running;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final AtomicBoolean cancelled = new AtomicBoolean();
    private NotificationManager notificationManager;
    private PowerManager.WakeLock wakeLock;
    private long lastNotificationUpdate;

    public static void start(Context context) {
        Intent intent = new Intent(context, GamePackInstallService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent);
        else context.startService(intent);
    }

    public static void cancel(Context context) {
        context.startService(new Intent(context, GamePackInstallService.class).setAction(ACTION_CANCEL));
    }

    public static boolean isRunning() {
        return running;
    }

    public static State getState() {
        return state;
    }

    public static void addListener(Listener listener) {
        listeners.add(listener);
        listener.onStateChanged(state);
    }

    public static void removeListener(Listener listener) {
        listeners.remove(listener);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        notificationManager = (NotificationManager)getSystemService(NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(CHANNEL_ID, getString(R.string.gamepack_channel_name), NotificationManager.IMPORTANCE_LOW);
            channel.setShowBadge(false);
            notificationManager.createNotificationChannel(channel);
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_CANCEL.equals(intent.getAction())) {
            cancelled.set(true);
            if (!running) stopSelf();
            return START_NOT_STICKY;
        }
        startForeground(NOTIFICATION_ID, buildNotification(state, true));
        if (!running) {
            running = true;
            cancelled.set(false);
            PowerManager powerManager = (PowerManager)getSystemService(POWER_SERVICE);
            wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "gamepack:install");
            wakeLock.acquire(6 * 60 * 60 * 1000L);
            new Thread(this::runInstall, "gamepack-install").start();
        }
        return START_NOT_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void runInstall() {
        try {
            install();
            publish(new State(Stage.DONE, 1000, getString(R.string.gamepack_ready)), true);
        }
        catch (Exception e) {
            String message = cancelled.get() ? getString(R.string.gamepack_cancelled) : e.getMessage();
            publish(new State(Stage.FAILED, 0, message != null ? message : e.toString()), true);
        }
        finally {
            running = false;
            if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
            mainHandler.post(() -> {
                stopForeground(false);
                notificationManager.notify(NOTIFICATION_ID, buildNotification(state, false));
                stopSelf();
            });
        }
    }

    private void install() throws Exception {
        GamePack pack = GamePack.load(this);
        GameSettings settings = new GameSettings(this);
        GamePackSetup setup = new GamePackSetup(this, pack, settings);

        // 1. System image (Wine, Box64/FEXCore, drivers) bundled in the APK.
        if (ImageFsInstaller.needsInstall(this)) {
            publish(new State(Stage.SYSTEM, 0, getString(R.string.gamepack_stage_system)), false);
            boolean ok = ImageFsInstaller.installFromAssetsBlocking(this, percent ->
                    publish(new State(Stage.SYSTEM, percent * 10, getString(R.string.gamepack_stage_system)), false));
            if (!ok) throw new IOException(getString(R.string.gamepack_error_system));
        }

        // 2. Hidden Wine container for the game.
        publish(new State(Stage.CONTAINER, -1, getString(R.string.gamepack_stage_container)), false);
        Container container = obtainContainer(setup);
        if (container == null) throw new IOException(getString(R.string.gamepack_error_container));

        // Already unpacked (e.g. the previous run failed after extraction)?
        String exe = setup.locateExecutable(container);
        if (exe != null && settings.getInstalledVersion() >= pack.packageVersion) {
            settings.setInstalled(exe, pack.packageVersion);
            return;
        }

        // 3. Download.
        File downloadDir = new File(getExternalFilesDir(null) != null ? getExternalFilesDir(null) : getFilesDir(), "downloads");
        downloadDir.mkdirs();
        checkFreeSpace(pack, downloadDir);

        GamePackDownloader downloader = new GamePackDownloader(cancelled);
        List<File> partFiles = new ArrayList<>();
        int partCount = pack.parts.size();
        for (int i = 0; i < partCount; i++) {
            GamePack.Part part = pack.parts.get(i);
            File outFile = new File(downloadDir, part.fileName);
            partFiles.add(outFile);
            File doneMarker = new File(downloadDir, part.fileName + ".done");
            if (doneMarker.isFile() && outFile.isFile()) continue;

            final int index = i;
            final String partLabel = partCount > 1 ? " (" + (i + 1) + "/" + partCount + ")" : "";
            downloader.download(part.url, outFile, (downloaded, total) -> {
                int progress = total > 0 ? (int)((index * 1000L + downloaded * 1000L / total) / partCount) : -1;
                String detail = getString(R.string.gamepack_stage_download) + partLabel + "  " + formatBytes(downloaded)
                        + (total > 0 ? " / " + formatBytes(total) : "");
                publish(new State(Stage.DOWNLOAD, progress, detail), false);
            });

            if (!part.sha256.isEmpty()) {
                publish(new State(Stage.VERIFY, -1, getString(R.string.gamepack_stage_verify) + partLabel), false);
                String actual = GamePackDownloader.sha256(outFile);
                if (!actual.equalsIgnoreCase(part.sha256)) {
                    outFile.delete();
                    throw new IOException(getString(R.string.gamepack_error_checksum, part.fileName));
                }
            }
            FileUtils.writeString(doneMarker, "ok");
        }

        // 4. Unpack straight into the container's drive C.
        File installDir = setup.getInstallDir(container);
        publish(new State(Stage.EXTRACT, 0, getString(R.string.gamepack_stage_extract)), false);
        new GamePackExtractor(cancelled).extract(partFiles, pack.archivePassword, installDir, (done, total) -> {
            int progress = total > 0 ? (int)(done * 1000L / total) : -1;
            String detail = getString(R.string.gamepack_stage_extract) + "  " + (progress >= 0 ? progress / 10 + "%" : "");
            publish(new State(Stage.EXTRACT, progress, detail), false);
        });

        exe = setup.locateExecutable(container);
        if (exe == null) throw new IOException(getString(R.string.gamepack_error_exe, pack.exe));
        settings.setInstalled(exe, pack.packageVersion);

        // 5. Free the space taken by the archive.
        for (File part : partFiles) {
            part.delete();
            new File(downloadDir, part.getName() + ".done").delete();
        }
    }

    private Container obtainContainer(GamePackSetup setup) throws InterruptedException {
        AtomicReference<Container> result = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);
        // ContainerManager posts its callback through a Handler, so it has to be driven from the main thread.
        mainHandler.post(() -> {
            ContainerManager manager = new ContainerManager(this);
            Container existing = setup.findContainer(manager);
            if (existing != null) {
                result.set(existing);
                latch.countDown();
            }
            else {
                setup.createContainer(manager, container -> {
                    result.set(container);
                    latch.countDown();
                });
            }
        });
        latch.await();
        return result.get();
    }

    private void checkFreeSpace(GamePack pack, File dir) throws IOException {
        if (pack.minFreeSpaceMb <= 0) return;
        long freeMb = new StatFs(dir.getPath()).getAvailableBytes() / (1024 * 1024);
        if (freeMb < pack.minFreeSpaceMb) {
            throw new IOException(getString(R.string.gamepack_error_space, pack.minFreeSpaceMb / 1024f, freeMb / 1024f));
        }
    }

    private void publish(State newState, boolean force) {
        state = newState;
        mainHandler.post(() -> {
            for (Listener listener : listeners) listener.onStateChanged(newState);
        });
        long now = System.currentTimeMillis();
        // Android throttles apps that update notifications too often.
        if (force || now - lastNotificationUpdate > 700) {
            lastNotificationUpdate = now;
            notificationManager.notify(NOTIFICATION_ID, buildNotification(newState, running));
        }
    }

    private Notification buildNotification(State state, boolean ongoing) {
        Intent openIntent = new Intent(this, GameLauncherActivity.class).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        int piFlags = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0);
        PendingIntent contentIntent = PendingIntent.getActivity(this, 0, openIntent, piFlags);

        String title;
        try {
            title = GamePack.load(this).title;
        }
        catch (Exception e) {
            title = getString(R.string.gamepack_app_name);
        }

        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentTitle(title)
                .setContentText(state.detail)
                .setContentIntent(contentIntent)
                .setOnlyAlertOnce(true)
                .setOngoing(ongoing)
                .setAutoCancel(!ongoing)
                .setCategory(NotificationCompat.CATEGORY_PROGRESS);

        if (ongoing) {
            if (state.progress < 0) builder.setProgress(0, 0, true);
            else builder.setProgress(1000, state.progress, false).setSubText(state.progress / 10 + "%");
            PendingIntent cancelIntent = PendingIntent.getService(this, 1,
                    new Intent(this, GamePackInstallService.class).setAction(ACTION_CANCEL), piFlags);
            builder.addAction(0, getString(R.string.gamepack_cancel), cancelIntent);
        }
        else {
            builder.setSmallIcon(state.stage == Stage.DONE ? android.R.drawable.stat_sys_download_done : android.R.drawable.stat_notify_error);
        }
        return builder.build();
    }

    static String formatBytes(long bytes) {
        if (bytes >= 1024L * 1024 * 1024) return String.format(Locale.US, "%.2f GB", bytes / (1024f * 1024 * 1024));
        return String.format(Locale.US, "%.0f MB", bytes / (1024f * 1024));
    }
}
