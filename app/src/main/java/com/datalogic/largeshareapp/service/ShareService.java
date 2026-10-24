package com.datalogic.largeshareapp.service;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.net.Uri;
import android.net.wifi.WifiManager;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.util.Log;

import androidx.core.app.NotificationCompat;
import androidx.core.app.ServiceCompat;
import androidx.core.content.ContextCompat;

import com.datalogic.largeshareapp.MainActivity;
import com.datalogic.largeshareapp.manager.SecureManager;
import com.datalogic.largeshareapp.manager.SeedManager;
import com.datalogic.largeshareapp.manager.SeekManager;
import com.datalogic.largeshareapp.manager.ShareManager;
import com.datalogic.largeshareapp.model.InformationMetadata;
import com.datalogic.largeshareapp.network.LocalAddresses;
import com.datalogic.largeshareapp.security.IdentityStore;
import com.datalogic.largeshareapp.storage.StorageLocations;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Hosts the share engine so a session survives the UI being closed, the screen turning off and
 * Doze. While a session runs the service is in the foreground (connectedDevice type), holds a
 * partial wake lock and a Wi-Fi lock, and shows a notification with progress and a Stop action.
 *
 * <p>The UI binds to it and talks only through {@link ShareApi}; all state flows back as
 * {@link SessionSnapshot}s. Engine callbacks arrive on worker threads, update the state under a
 * lock and schedule one coalesced publish on the main thread.
 */
public class ShareService extends Service implements ShareApi {
    private static final String TAG = "ShareService";
    public static final String ACTION_STOP = "com.datalogic.largeshareapp.action.STOP";
    private static final String CHANNEL_ID = "share_session";
    private static final int NOTIFICATION_ID = 1001;
    private static final long PUBLISH_COALESCE_MS = 150;
    private static final long NOTIFY_MIN_INTERVAL_MS = 1000;
    private static final long WAKE_LOCK_MAX_MS = 12 * 60 * 60 * 1000L;
    private static final long ADDRESS_REFRESH_MS = 3_000L;

    /** Binder handed to clients in the same process. */
    public final class LocalBinder extends Binder {
        public ShareApi api() {
            return ShareService.this;
        }
    }

    private final IBinder mBinder = new LocalBinder();
    private final Handler mMain = new Handler(Looper.getMainLooper());
    private final ExecutorService mIo = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "share-service-io");
        t.setDaemon(true);
        return t;
    });
    private final ScheduledExecutorService mNet = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "share-service-net");
        t.setDaemon(true);
        return t;
    });
    private final AtomicBoolean mAddressRefreshPending = new AtomicBoolean();
    private final Set<Listener> mListeners = new CopyOnWriteArraySet<>();

    private final Object mLock = new Object();
    private final SessionSnapshot.Builder mState = new SessionSnapshot.Builder(); // guarded by mLock
    private boolean mPublishPending;                                             // guarded by mLock

    // Main-thread state (mShareManager is also read by engine callbacks, hence volatile).
    private volatile ShareManager mShareManager;
    private SessionConfig mPendingStart;
    private boolean mForeground;
    private long mLastNotifyAt;
    private SessionSnapshot.Phase mLastNotifiedPhase;
    private PowerManager.WakeLock mWakeLock;
    private WifiManager.WifiLock mWifiLock;

    // Prepared payload (written by the io thread, read on main).
    private volatile File mPreparedFile;
    private volatile InformationMetadata mPreparedMeta;

    // ------------------------------------------------------------------ service lifecycle

    @Override
    public void onCreate() {
        super.onCreate();
        createChannel();
        synchronized (mLock) {
            mState.deviceId = IdentityStore.getDeviceId(this);
            mState.servePort = ShareManager.HTTP_PORT;
        }
        // Addresses change when Wi-Fi roams or a Wi-Fi Direct group comes and goes; polling covers
        // every interface with one cheap call and runs only while the service is alive.
        mNet.scheduleWithFixedDelay(this::refreshAddresses, 0, ADDRESS_REFRESH_MS, TimeUnit.MILLISECONDS);
    }

    private void refreshAddressesSoon() {
        if (!mAddressRefreshPending.compareAndSet(false, true)) {
            return; // one refresh already queued covers this change too
        }
        try {
            // A short delay lets DHCP on a just-joined group hand out the address first.
            mNet.schedule(() -> {
                mAddressRefreshPending.set(false);
                refreshAddresses();
            }, 1, TimeUnit.SECONDS);
        } catch (RejectedExecutionException ignored) {
            // service destroyed
        }
    }

    private void refreshAddresses() {
        List<SessionSnapshot.NetAddress> current = new ArrayList<>();
        for (LocalAddresses.Address a : LocalAddresses.current()) {
            current.add(new SessionSnapshot.NetAddress(a.label, a.ip));
        }
        synchronized (mLock) {
            if (!current.equals(mState.addresses)) {
                mState.addresses = current;
                schedulePublishLocked();
            }
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return mBinder;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            stop();
            return START_NOT_STICKY;
        }
        if (mShareManager != null) {
            enterForeground(); // keep the promise made by startForegroundService()
        } else {
            stopSelf(startId); // nothing to run (e.g. restarted after the session ended)
        }
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        if (mShareManager != null) {
            mShareManager.stopSharing();
            mShareManager = null;
        }
        releaseLocks();
        mIo.shutdownNow();
        mNet.shutdownNow();
        mMain.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    // ------------------------------------------------------------------ ShareApi

    @Override
    public void addListener(Listener listener) {
        if (listener != null && mListeners.add(listener)) {
            listener.onSnapshot(getSnapshot());
        }
    }

    @Override
    public void removeListener(Listener listener) {
        mListeners.remove(listener);
    }

    @Override
    public SessionSnapshot getSnapshot() {
        synchronized (mLock) {
            return mState.build();
        }
    }

    @Override
    public String getFleetKey() {
        return IdentityStore.getFleetSecret(this);
    }

    @Override
    public void prepareFile(Uri uri) {
        prepare(uri, null);
    }

    @Override
    public void prepareFile(String path) {
        prepare(null, path);
    }

    private void prepare(Uri uri, String path) {
        synchronized (mLock) {
            if (mState.active) {
                mState.message = "Stop the session before choosing another file";
                schedulePublishLocked();
                return;
            }
            mState.preparing = true;
            mState.preparePercent = 0;
            mState.file = null;
            mState.message = null;
            schedulePublishLocked();
        }
        mPreparedFile = null;
        mPreparedMeta = null;
        mIo.execute(() -> {
            File file = path != null ? new File(path) : StorageLocations.resolveToFile(this, uri);
            if (file == null && uri != null) {
                try {
                    file = StorageLocations.copyToAppStorage(this, uri); // provider exposes no path
                } catch (IOException e) {
                    Log.e(TAG, "Cannot access " + uri, e);
                }
            }
            InformationMetadata meta = file == null ? null : SecureManager.createFileMetadata(
                    file, SecureManager.CHUNK_SIZE_512KB, (done, total) -> {
                        int percent = done * 100 / total;
                        synchronized (mLock) {
                            if (percent != mState.preparePercent) {
                                mState.preparePercent = percent;
                                schedulePublishLocked();
                            }
                        }
                    });
            mPreparedFile = meta != null ? file : null;
            mPreparedMeta = meta;
            synchronized (mLock) {
                mState.preparing = false;
                if (meta == null) {
                    mState.file = null;
                    mState.message = "Cannot read the selected file";
                } else {
                    mState.file = new SessionSnapshot.FileInfo(file.getPath(), meta.getFileName(),
                            meta.getFileSize(), meta.getChunkSize(), meta.getChunkCount(), meta.getFileHash());
                }
                schedulePublishLocked();
            }
            mMain.post(this::startPendingIfReady);
        });
    }

    private void startPendingIfReady() {
        SessionConfig pending = mPendingStart;
        mPendingStart = null;
        if (pending != null) {
            if (mPreparedMeta != null) {
                start(pending);
            } else {
                reportRefusal("Cannot start: the file could not be prepared");
            }
        }
    }

    @Override
    public boolean start(SessionConfig config) {
        if (config == null || config.role == null || config.mode == null) {
            return reportRefusal("Choose a role and a discovery mode first");
        }
        if (mShareManager != null) {
            return reportRefusal("A session is already running");
        }
        boolean root = config.role == SessionSnapshot.Role.ROOT;
        if (root) {
            File prepared = mPreparedFile;
            boolean samePath = prepared != null && config.filePath != null
                    && prepared.getPath().equals(new File(config.filePath).getPath());
            if (config.filePath != null && !samePath) {
                // Prepare first, then start automatically.
                mPendingStart = config.withoutFilePath();
                prepareFile(config.filePath);
                return true;
            }
            if (mPreparedMeta == null) {
                if (isPreparing()) {
                    mPendingStart = config.withoutFilePath();
                    return true;
                }
                return reportRefusal("Select a file to share first");
            }
        }

        IdentityStore.setFleetSecret(this, config.fleetKey);
        ShareManager.DiscoveryMode mode = config.mode == SessionSnapshot.Mode.WIFI_DIRECT
                ? ShareManager.DiscoveryMode.WIFI_DIRECT : ShareManager.DiscoveryMode.NSD;
        ShareManager manager = new ShareManager(this, root ? mPreparedFile : null, mode);
        if (config.groupCapacity > 0) {
            manager.setGroupCapacity(config.groupCapacity);
        }
        if (root) {
            manager.setPrecomputedMetadata(mPreparedMeta);
        }
        StringBuilder ignored = new StringBuilder();
        if (!root) {
            for (String peer : config.staticPeers) {
                if (!manager.addStaticPeer(peer)) {
                    ignored.append(ignored.length() == 0 ? "" : ", ").append(peer);
                }
            }
        }
        wireListeners(manager);

        synchronized (mLock) {
            mState.active = true;
            mState.role = config.role;
            mState.mode = config.mode;
            mState.phase = SessionSnapshot.Phase.PREPARING;
            mState.detail = "Starting";
            mState.message = ignored.length() == 0 ? null : "Ignored malformed peer(s): " + ignored;
            mState.completedChunks = 0;
            mState.totalChunks = 0;
            mState.bytesPerSec = 0;
            mState.activePeers = 0;
            mState.knownPeers = 0;
            mState.peers.clear();
            schedulePublishLocked();
        }
        mShareManager = manager;
        // Become a started + foreground service: the session now outlives the UI.
        ContextCompat.startForegroundService(this, new Intent(this, ShareService.class));
        enterForeground();
        acquireLocks(config.mode != SessionSnapshot.Mode.WIFI_DIRECT);
        manager.startSharing(root ? ShareManager.DiscoveryRole.SEEDER : ShareManager.DiscoveryRole.SEEKER);
        Log.i(TAG, "Session started: " + config.role + " / " + config.mode);
        return true;
    }

    @Override
    public void stop() {
        mPendingStart = null;
        ShareManager manager = mShareManager;
        mShareManager = null;
        if (manager != null) {
            manager.stopSharing();
        }
        endSession(SessionSnapshot.Phase.IDLE, "Stopped");
    }

    // ------------------------------------------------------------------ engine callbacks

    private void wireListeners(ShareManager manager) {
        manager.addStatusListener((phase, detail) -> {
            SessionSnapshot.Phase mapped = SessionSnapshot.Phase.valueOf(phase.name());
            synchronized (mLock) {
                if (manager != mShareManager && mShareManager != null) {
                    return; // late callback from a previous session
                }
                mState.phase = mapped;
                mState.detail = detail;
                schedulePublishLocked();
            }
            // Status changes follow group joins / leaves and network changes: show the new
            // addresses at once instead of at the next poll.
            refreshAddressesSoon();
            if (mapped == SessionSnapshot.Phase.LEFT || mapped == SessionSnapshot.Phase.FAILED) {
                mMain.post(() -> onSessionFinished(manager, mapped, detail));
            }
        });
        manager.addSeekEventListener(new SeekManager.SeekEventListener() {
            @Override
            public void onChunkBatchLeeched(int completed, int total) {
                synchronized (mLock) {
                    mState.completedChunks = completed;
                    mState.totalChunks = total;
                    schedulePublishLocked();
                }
            }

            @Override
            public void onStats(double bytesPerSec, int activePeers, int knownPeers) {
                synchronized (mLock) {
                    mState.bytesPerSec = bytesPerSec;
                    mState.activePeers = activePeers;
                    mState.knownPeers = knownPeers;
                    schedulePublishLocked();
                }
            }
        });
        manager.addPeersTrackingListener((SeedManager.PeersTrackingListener) (deviceId, completed, total) -> {
            synchronized (mLock) {
                mState.peers.put(deviceId, new SessionSnapshot.PeerProgress(deviceId, completed, total));
                schedulePublishLocked();
            }
        });
    }

    /** The engine ended on its own (download finished and left, or failed). */
    private void onSessionFinished(ShareManager manager, SessionSnapshot.Phase phase, String detail) {
        if (manager != mShareManager) {
            return;
        }
        mShareManager = null;
        manager.stopSharing();
        endSession(phase, detail);
    }

    private void endSession(SessionSnapshot.Phase phase, String detail) {
        synchronized (mLock) {
            mState.active = false;
            mState.bytesPerSec = 0;
            mState.activePeers = 0;
            // Keep a completed / failed outcome visible instead of overwriting it with "Stopped".
            if (phase != SessionSnapshot.Phase.IDLE || (mState.phase != SessionSnapshot.Phase.LEFT
                    && mState.phase != SessionSnapshot.Phase.FAILED)) {
                mState.phase = phase;
                mState.detail = detail;
            }
            schedulePublishLocked();
        }
        releaseLocks();
        if (mForeground) {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE);
            mForeground = false;
        }
        stopSelf(); // stays alive only while the UI is bound
    }

    private boolean isPreparing() {
        synchronized (mLock) {
            return mState.preparing;
        }
    }

    private boolean reportRefusal(String why) {
        synchronized (mLock) {
            mState.message = why;
            schedulePublishLocked();
        }
        return false;
    }

    // ------------------------------------------------------------------ publishing

    private void schedulePublishLocked() {
        if (!mPublishPending) {
            mPublishPending = true;
            mMain.postDelayed(this::publishNow, PUBLISH_COALESCE_MS);
        }
    }

    private void publishNow() {
        SessionSnapshot snapshot;
        synchronized (mLock) {
            mPublishPending = false;
            snapshot = mState.build();
        }
        for (Listener l : mListeners) {
            try {
                l.onSnapshot(snapshot);
            } catch (RuntimeException e) {
                Log.e(TAG, "Listener failed", e);
            }
        }
        if (mForeground) {
            updateNotification(snapshot, false);
        }
    }

    // ------------------------------------------------------------------ foreground & locks

    private void enterForeground() {
        Notification notification = buildNotification(getSnapshot());
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);
        mForeground = true;
    }

    private void updateNotification(SessionSnapshot s, boolean force) {
        long now = System.currentTimeMillis();
        if (!force && s.phase == mLastNotifiedPhase && now - mLastNotifyAt < NOTIFY_MIN_INTERVAL_MS) {
            return;
        }
        mLastNotifyAt = now;
        mLastNotifiedPhase = s.phase;
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null) {
            nm.notify(NOTIFICATION_ID, buildNotification(s));
        }
    }

    private Notification buildNotification(SessionSnapshot s) {
        boolean root = s.role == SessionSnapshot.Role.ROOT;
        String title;
        if (root) {
            int done = 0;
            for (SessionSnapshot.PeerProgress p : s.peers.values()) {
                done += p.isDone() ? 1 : 0;
            }
            title = String.format(Locale.US, "Sharing%s - %d peer(s), %d done",
                    s.file != null ? " " + s.file.name : "", s.peers.size(), done);
        } else {
            title = s.totalChunks > 0
                    ? String.format(Locale.US, "Receiving - %d%% (%.1f MB/s)", s.percent(), s.bytesPerSec / (1024 * 1024))
                    : "Receiving - looking for peers";
        }
        PendingIntent open = PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        PendingIntent stop = PendingIntent.getService(this, 1,
                new Intent(this, ShareService.class).setAction(ACTION_STOP),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        NotificationCompat.Builder b = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(root ? android.R.drawable.stat_sys_upload : android.R.drawable.stat_sys_download)
                .setContentTitle(title)
                .setContentText(s.phase + " - " + s.detail)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
                .setContentIntent(open)
                .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", stop);
        if (!root) {
            if (s.totalChunks > 0) {
                b.setProgress(100, s.percent(), false);
            } else {
                b.setProgress(0, 0, true);
            }
        }
        return b.build();
    }

    private void createChannel() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null) {
            NotificationChannel channel = new NotificationChannel(CHANNEL_ID, "Share session",
                    NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("Progress of the running share session");
            nm.createNotificationChannel(channel);
        }
    }

    /**
     *  wifiLock also keep the Wi-Fi station out of power save. Not in Wi-Fi Direct mode: on
     *                 recent Android the lock becomes "low latency", which stops Wi-Fi scans, and the
     *                 swarm finds groups by scanning (the lock does not affect the p2p interface).
     */
    @SuppressWarnings("deprecation")
    private void acquireLocks(boolean wifiLock) {
        if (mWakeLock == null) {
            PowerManager pm = getSystemService(PowerManager.class);
            mWakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "LargeShare:session");
            mWakeLock.setReferenceCounted(false);
        }
        mWakeLock.acquire(WAKE_LOCK_MAX_MS);
        if (!wifiLock) {
            return;
        }
        if (mWifiLock == null) {
            WifiManager wm = getApplicationContext().getSystemService(WifiManager.class);
            if (wm != null) {
                // Keeps the radio out of power save so transfers do not stall with the screen off.
                mWifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "LargeShare:wifi");
                mWifiLock.setReferenceCounted(false);
            }
        }
        if (mWifiLock != null) {
            mWifiLock.acquire();
        }
    }

    private void releaseLocks() {
        if (mWakeLock != null && mWakeLock.isHeld()) {
            mWakeLock.release();
        }
        if (mWifiLock != null && mWifiLock.isHeld()) {
            mWifiLock.release();
        }
    }
}
