package com.felix.pricealert;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.VibrationEffect;
import android.os.Vibrator;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.List;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;

/**
 * Keeps one live WebSocket to Kraken open in the background, checks every
 * new price against your alerts, and fires a notification when one hits.
 */
public class PriceService extends Service {

    static final String ACTION_RESTART = "com.felix.pricealert.RESTART";
    static final String ACTION_STOP = "com.felix.pricealert.STOP";
    static final String ACTION_SILENCE = "com.felix.pricealert.SILENCE";
    static final String EXTRA_SILENCE = "silence";
    private static final long MAX_RING_MS = 5 * 60 * 1000;
    private static final String CH_LIVE = "live";
    private static final String CH_ALERT = "alarm";
    private static final int NOTIF_ID = 1;
    private static final String FEED = "wss://ws.kraken.com/v2";

    private final Handler main = new Handler(Looper.getMainLooper());
    private OkHttpClient client;
    private WebSocket ws;
    private PowerManager.WakeLock wakeLock;
    private NotificationManager nm;
    private volatile boolean running;
    private volatile int connGen = 0;
    private int backoffMs = 2000;
    private long lastNotifUpdate = 0;
    private String lastError = null;
    private MediaPlayer player;
    private Vibrator vibrator;
    private final Runnable autoStopRing = this::stopRinging;

    // ---- helpers used by the screen ----

    public static void start(Context c) {
        Store.init(c);
        Store.setEnabled(true);
        c.startForegroundService(new Intent(c, PriceService.class));
    }

    public static void restart(Context c) {
        Store.init(c);
        if (!Store.isEnabled()) return;
        c.startForegroundService(new Intent(c, PriceService.class).setAction(ACTION_RESTART));
    }

    /** Stops the alarm sound and vibration. */
    public static void silence(Context c) {
        try {
            c.startService(new Intent(c, PriceService.class).setAction(ACTION_SILENCE));
        } catch (Exception ignored) {
        }
    }

    public static void stop(Context c) {
        Store.init(c);
        Store.setEnabled(false);
        c.stopService(new Intent(c, PriceService.class));
    }

    // ---- lifecycle ----

    @Override
    public void onCreate() {
        super.onCreate();
        Store.init(this);
        nm = getSystemService(NotificationManager.class);

        NotificationChannel live = new NotificationChannel(CH_LIVE, "Live prices", NotificationManager.IMPORTANCE_LOW);
        live.setShowBadge(false);
        nm.createNotificationChannel(live);

        nm.deleteNotificationChannel("alerts"); // old v1.0 channel
        NotificationChannel alerts = new NotificationChannel(CH_ALERT, "Price alarms", NotificationManager.IMPORTANCE_HIGH);
        alerts.setSound(null, null);        // the app plays the looping alarm itself
        alerts.enableVibration(false);      // ...and the repeating vibration
        alerts.enableLights(true);
        alerts.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
        nm.createNotificationChannel(alerts);

        vibrator = getSystemService(Vibrator.class);

        client = new OkHttpClient.Builder()
                .pingInterval(20, TimeUnit.SECONDS)
                .connectTimeout(15, TimeUnit.SECONDS)
                .build();

        PowerManager pm = getSystemService(PowerManager.class);
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "PriceAlert::feed");
        wakeLock.setReferenceCounted(false);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent != null ? intent.getAction() : null;

        if (ACTION_SILENCE.equals(action)) {
            stopRinging();
            if (!running) {
                stopSelf();
                return START_NOT_STICKY;
            }
            return START_STICKY;
        }

        if (ACTION_STOP.equals(action) || (intent == null && !Store.isEnabled())) {
            Store.setEnabled(false);
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf();
            return START_NOT_STICKY;
        }

        Notification n = buildLiveNotification();
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
            } else {
                startForeground(NOTIF_ID, n);
            }
        } catch (Exception e) {
            // Android refused a background start; the app restarts it next time it opens.
            stopSelf();
            return START_NOT_STICKY;
        }

        running = true;
        if (!wakeLock.isHeld()) wakeLock.acquire();

        if (ws == null || ACTION_RESTART.equals(action)) {
            backoffMs = 2000;
            connect();
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        running = false;
        connGen++;
        stopRinging();
        main.removeCallbacksAndMessages(null);
        if (ws != null) ws.close(1000, "bye");
        ws = null;
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        PriceHub.live = false;
        PriceHub.status = "Stopped";
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }

    // ---- feed ----

    private void connect() {
        final int gen = ++connGen;
        if (ws != null) { ws.cancel(); ws = null; }
        lastError = null;

        final List<String> syms = Store.symbols();
        if (syms.isEmpty()) {
            setStatus("Add a symbol to start", false);
            return;
        }
        setStatus("Connecting…", false);

        Request req = new Request.Builder().url(FEED).build();
        ws = client.newWebSocket(req, new WebSocketListener() {
            @Override
            public void onOpen(WebSocket w, Response r) {
                if (gen != connGen) return;
                try {
                    JSONObject params = new JSONObject();
                    params.put("channel", "ticker");
                    params.put("symbol", new JSONArray(syms));
                    JSONObject sub = new JSONObject();
                    sub.put("method", "subscribe");
                    sub.put("params", params);
                    w.send(sub.toString());
                } catch (Exception ignored) {
                }
                main.post(() -> backoffMs = 2000);
                setStatus("Live", true);
            }

            @Override
            public void onMessage(WebSocket w, String text) {
                if (gen != connGen) return;
                handle(text);
            }

            @Override
            public void onClosing(WebSocket w, int code, String reason) {
                w.close(1000, null);
            }

            @Override
            public void onClosed(WebSocket w, int code, String reason) {
                if (gen != connGen) return;
                setStatus("Reconnecting…", false);
                scheduleReconnect(gen);
            }

            @Override
            public void onFailure(WebSocket w, Throwable t, Response r) {
                if (gen != connGen) return;
                setStatus("No connection – retrying…", false);
                scheduleReconnect(gen);
            }
        });
    }

    private void scheduleReconnect(final int gen) {
        main.post(() -> {
            if (!running || gen != connGen) return;
            int delay = backoffMs;
            backoffMs = Math.min(backoffMs * 2, 30000);
            main.postDelayed(() -> {
                if (running && gen == connGen) connect();
            }, delay);
        });
    }

    private void handle(String text) {
        try {
            JSONObject m = new JSONObject(text);

            if ("subscribe".equals(m.optString("method")) && !m.optBoolean("success", true)) {
                lastError = m.optString("error", "unknown symbol");
                setStatus("Live", true);
                return;
            }
            if (!"ticker".equals(m.optString("channel"))) return;

            JSONArray data = m.optJSONArray("data");
            if (data == null) return;
            for (int i = 0; i < data.length(); i++) {
                JSONObject o = data.getJSONObject(i);
                String sym = o.getString("symbol");
                double last = o.getDouble("last");
                double chg = o.optDouble("change_pct", Double.NaN);
                PriceHub.put(sym, last, chg);
                for (Store.Alert a : Store.checkPrice(sym, last)) fireAlert(a, last);
            }

            long now = System.currentTimeMillis();
            if (now - lastNotifUpdate > 3000) {
                lastNotifUpdate = now;
                nm.notify(NOTIF_ID, buildLiveNotification());
            }
        } catch (Exception ignored) {
        }
    }

    private void setStatus(String s, boolean live) {
        PriceHub.status = (lastError != null && live) ? s + " · " + lastError : s;
        PriceHub.live = live;
        if (running && nm != null) nm.notify(NOTIF_ID, buildLiveNotification());
    }

    // ---- notifications ----

    private PendingIntent openApp() {
        Intent i = new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        return PendingIntent.getActivity(this, 0, i, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    private Notification buildLiveNotification() {
        StringBuilder sb = new StringBuilder();
        for (String sym : Store.symbols()) {
            PriceHub.Tick t = PriceHub.get(sym);
            if (t == null) continue;
            if (sb.length() > 0) sb.append("   ");
            sb.append(PriceHub.name(sym)).append(' ').append(PriceHub.fmt(t.last));
        }
        if (sb.length() == 0) sb.append("Waiting for prices…");

        PendingIntent stop = PendingIntent.getService(this, 1,
                new Intent(this, PriceService.class).setAction(ACTION_STOP),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        return new Notification.Builder(this, CH_LIVE)
                .setSmallIcon(R.drawable.ic_stat)
                .setContentTitle("Watching prices · " + PriceHub.status)
                .setContentText(sb.toString())
                .setStyle(new Notification.BigTextStyle().bigText(sb.toString()))
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setShowWhen(false)
                .setContentIntent(openApp())
                .addAction(new Notification.Action.Builder(null, "Stop", stop).build())
                .build();
    }

    private void fireAlert(Store.Alert a, double last) {
        String title = PriceHub.name(a.symbol) + (a.above ? " rose above " : " fell below ") + PriceHub.fmt(a.price);
        String body = "Now " + PriceHub.fmt(last) + "  (" + a.symbol + ")";

        PendingIntent silence = PendingIntent.getService(this, 2,
                new Intent(this, PriceService.class).setAction(ACTION_SILENCE),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Intent open = new Intent(this, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra(EXTRA_SILENCE, true);
        PendingIntent openAndSilence = PendingIntent.getActivity(this, 3, open,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        Notification n = new Notification.Builder(this, CH_ALERT)
                .setSmallIcon(R.drawable.ic_stat)
                .setContentTitle(title)
                .setContentText(body)
                .setCategory(Notification.CATEGORY_ALARM)
                .setAutoCancel(true)
                .setContentIntent(openAndSilence)
                .setDeleteIntent(silence)
                .addAction(new Notification.Action.Builder(null, "Stop ringing", silence).build())
                .build();
        nm.notify((int) (a.id % 1_000_000) + 100, n);

        main.post(this::startRinging);
    }

    // ---- alarm sound + vibration ----

    private void startRinging() {
        AudioAttributes alarm = new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build();

        if (!Store.ringEnabled()) {
            // single buzz only
            if (vibrator != null && vibrator.hasVibrator()) {
                vibrator.vibrate(VibrationEffect.createWaveform(new long[]{0, 400, 200, 400}, -1), alarm);
            }
            return;
        }

        PriceHub.ringing = true;
        main.removeCallbacks(autoStopRing);
        main.postDelayed(autoStopRing, MAX_RING_MS);

        if (player == null) {
            try {
                Uri uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM);
                if (uri == null) uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE);
                if (uri == null) uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION);
                player = new MediaPlayer();
                player.setAudioAttributes(alarm);
                player.setDataSource(this, uri);
                player.setLooping(true);
                player.prepare();
                player.start();
            } catch (Exception e) {
                if (player != null) player.release();
                player = null;
            }
        }
        if (vibrator != null && vibrator.hasVibrator()) {
            // vibrate 0.8 s, pause 0.5 s, repeat until stopped
            vibrator.vibrate(VibrationEffect.createWaveform(new long[]{0, 800, 500}, 0), alarm);
        }
    }

    private void stopRinging() {
        main.removeCallbacks(autoStopRing);
        PriceHub.ringing = false;
        if (player != null) {
            try {
                player.stop();
            } catch (Exception ignored) {
            }
            player.release();
            player = null;
        }
        if (vibrator != null) vibrator.cancel();
    }
}
