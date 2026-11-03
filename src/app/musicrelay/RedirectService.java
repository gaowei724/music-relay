package app.musicrelay;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ComponentName;
import android.content.Intent;
import android.content.SharedPreferences;
import android.media.AudioManager;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.util.Log;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URLEncoder;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.json.JSONObject;

// Always-on service (VPN-style). Stays resident with a status notification whose
// color shows state: grey = not mirroring, green = mirroring OK, red = sync error.
// Mirroring itself is a toggle: when on, the phone is muted and the tablet follows it.
public class RedirectService extends Service {
    static final String TAG = "MusicRelayMirror";
    static final String AM = "com.apple.android.music";
    static final String ACTION_TOGGLE = "app.musicrelay.TOGGLE";
    static final String ACTION_STOP = "app.musicrelay.STOP";
    static final String ACTION_RENOTIFY = "app.musicrelay.RENOTIFY";
    // Notification buttons: they drive the phone's own app, and the mirror carries it over.
    static final String ACTION_PREV = "app.musicrelay.PREV";
    static final String ACTION_PLAYPAUSE = "app.musicrelay.PLAYPAUSE";
    static final String ACTION_NEXT = "app.musicrelay.NEXT";
    private boolean curPlaying;

    // The service runs in its own process (":mirror", like Clash Meta's background core),
    // so clearing the app window from recents does not stop mirroring. The UI and the tile
    // live in the main process and read the state from this small file instead of statics.
    // If we muted the phone and then died without undoing it (killed, updated), give the
    // volume back. Called from the app and from the service start.
    // Mute the phone the instant mirroring is switched on (from the tile or the app), before
    // the service process has even started, so a playing song is not heard for a moment.
    static void muteNow(android.content.Context c) {
        android.content.SharedPreferences p = c.getSharedPreferences("am", MODE_PRIVATE);
        AudioManager a = c.getSystemService(AudioManager.class);
        try {
            int v = a.getStreamVolume(AudioManager.STREAM_MUSIC);
            if (v > 0) p.edit().putInt("savedVol", v).apply();
            a.setStreamVolume(AudioManager.STREAM_MUSIC, 0, 0);
            p.edit().putBoolean("mutedByUs", true).putBoolean("preMuted", true).apply();
        } catch (Exception ignored) {
        }
    }

    // Give the phone its volume back right away (from a foreground context).
    static void unmuteNow(android.content.Context c) {
        android.content.SharedPreferences p = c.getSharedPreferences("am", MODE_PRIVATE);
        if (!p.getBoolean("mutedByUs", false)) return;
        AudioManager a = c.getSystemService(AudioManager.class);
        int v = p.getInt("savedVol", a.getStreamMaxVolume(AudioManager.STREAM_MUSIC) / 2);
        try {
            if (a.getStreamVolume(AudioManager.STREAM_MUSIC) == 0) {
                a.setStreamVolume(AudioManager.STREAM_MUSIC, v, 0);
            }
        } catch (Exception ignored) {
        }
        // vivo may silently ignore the change; keep the flag so the app can offer a retry.
        if (a.getStreamVolume(AudioManager.STREAM_MUSIC) > 0) {
            p.edit().putBoolean("mutedByUs", false).apply();
        }
    }

    // Muted by a mirror session and still at zero.
    static boolean leftMuted(android.content.Context c) {
        return c.getSharedPreferences("am", MODE_PRIVATE).getBoolean("mutedByUs", false)
                && c.getSystemService(AudioManager.class).getStreamVolume(AudioManager.STREAM_MUSIC) == 0;
    }

    static void restoreVolumeIfStale(android.content.Context c) {
        restoreVolume(c, false);
    }

    private static void restoreVolume(android.content.Context c, boolean force) {
        android.content.SharedPreferences p = c.getSharedPreferences("am", MODE_PRIVATE);
        if (!p.getBoolean("mutedByUs", false) || (!force && isOn(c))) return;
        AudioManager a = c.getSystemService(AudioManager.class);
        int v = p.getInt("savedVol", a.getStreamMaxVolume(AudioManager.STREAM_MUSIC) / 2);
        try {
            if (a.getStreamVolume(AudioManager.STREAM_MUSIC) == 0) {
                a.setStreamVolume(AudioManager.STREAM_MUSIC, v, 0);
            }
        } catch (Exception ignored) {
        }
        // vivo may silently ignore the change; keep the flag so the app can offer a retry.
        if (a.getStreamVolume(AudioManager.STREAM_MUSIC) > 0) {
            p.edit().putBoolean("mutedByUs", false).apply();
        }
    }

    // Which phone app is mirrored: one of AgentService.APPS, or "auto" (whichever plays).
    // Kept in a file, not prefs, because this service runs in another process.
    static final String AUTO = "auto";

    static String source(android.content.Context c) {
        try (java.io.FileInputStream in = c.openFileInput("mirror_source")) {
            byte[] b = new byte[64];
            String s = new String(b, 0, Math.max(in.read(b), 0)).trim();
            return AUTO.equals(s) || AgentService.supported(s) ? s : AgentService.AM;
        } catch (Exception e) {
            return AgentService.AM;
        }
    }

    static void setSource(android.content.Context c, String s) {
        try (java.io.FileOutputStream out = c.openFileOutput("mirror_source", MODE_PRIVATE)) {
            out.write(s.getBytes());
        } catch (Exception ignored) {
        }
    }

    static boolean isOn(android.content.Context c) {
        return readState(c)[0] == 1 && serviceAlive(c);
    }

    static int healthOf(android.content.Context c) {
        return isOn(c) ? readState(c)[1] : IDLE;
    }

    private static int[] readState(android.content.Context c) {
        try (java.io.FileInputStream in = c.openFileInput("mirror_state")) {
            byte[] b = new byte[8];
            int n = in.read(b);
            String[] p = new String(b, 0, Math.max(n, 0)).trim().split(",");
            return new int[] {Integer.parseInt(p[0]), Integer.parseInt(p[1])};
        } catch (Exception e) {
            return new int[] {0, IDLE};
        }
    }

    @SuppressWarnings("deprecation")
    private static boolean serviceAlive(android.content.Context c) {
        for (android.app.ActivityManager.RunningServiceInfo s
                : c.getSystemService(android.app.ActivityManager.class).getRunningServices(50)) {
            if (s.service.getClassName().equals(RedirectService.class.getName())) return true;
        }
        return false;
    }

    private void writeState() {
        try (java.io.FileOutputStream out = openFileOutput("mirror_state", MODE_PRIVATE)) {
            out.write(((mirroring ? 1 : 0) + "," + health).getBytes());
        } catch (Exception ignored) {
        }
    }
    static final long TICK_MS = 1000;
    static final int ALIGN_EVERY = 6;
    static final long ALIGN_TOLERANCE = 2500;
    static final long SEEK_JUMP = 1500;

    static final String CHANNEL = "mirror_status";
    static final long IDLE_GRACE_MS = 15 * 60 * 1000; // stay awake this long after music stops
    static final int IDLE = 0, OK = 1, ERROR = 2;
    private long lastActive;
    static volatile boolean running;
    static volatile boolean mirroring;
    static volatile int health = IDLE;

    private MediaSessionManager msm;
    private MediaController current;
    private AudioManager am;
    private PowerManager.WakeLock wake;
    private int savedVol = -1;
    private String lastId = "";
    private int lastState = -1;
    private long lastPlayingPos = -1;
    private long lastPlayingWall = 0;
    private int tick = 0;
    private int shownHealth = -1;
    private String curTitle = "";
    private String curArtist = "";
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler h = new Handler(Looper.getMainLooper());

    private final MediaSessionManager.OnActiveSessionsChangedListener sessionsChanged =
            this::bind;

    private final MediaController.Callback cb = new MediaController.Callback() {
        @Override public void onPlaybackStateChanged(PlaybackState s) {
            if (mirroring) sync();
        }
        @Override public void onMetadataChanged(MediaMetadata m) {
            if (mirroring) sync();
        }
        @Override public void onSessionDestroyed() {
            current = null;
        }
    };

    private final Runnable loop = new Runnable() {
        @Override public void run() {
            try {
                if (mirroring) {
                    // Apple Music may recreate its session (e.g. playing from search, or after
                    // being restarted in the background); the change callback is throttled in
                    // the background, so rescan every tick.
                    rescan();
                    sync();
                    // Follow Apple Music's lifecycle: keep the CPU awake while it plays and
                    // for a grace period afterwards, then let the system save power.
                    MediaController mc = current;
                    PlaybackState ps = mc == null ? null : mc.getPlaybackState();
                    long now = SystemClock.elapsedRealtime();
                    if (ps != null && ps.getState() == PlaybackState.STATE_PLAYING) lastActive = now;
                    boolean active = now - lastActive < IDLE_GRACE_MS;
                    if (active && !wake.isHeld()) wake.acquire();
                    else if (!active && wake.isHeld()) wake.release();
                    ++tick;
                    if (tick % ALIGN_EVERY == 0) alignProgress();
                    // After an error, probe the tablet every few seconds so the status recovers.
                    if (health == ERROR && tick % 3 == 0) {
                        io.execute(() -> setHealth(probe() ? OK : ERROR));
                    }
                }
            } finally {
                boolean busy = mirroring
                        && SystemClock.elapsedRealtime() - lastActive < IDLE_GRACE_MS;
                h.postDelayed(this, busy ? TICK_MS : 4000);
            }
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        // A fresh start means any earlier mirroring session is gone; undo its mute first,
        // unless the tile/app has just muted on purpose for the session we are starting.
        android.content.SharedPreferences sp = getSharedPreferences("am", MODE_PRIVATE);
        if (sp.getBoolean("preMuted", false)) sp.edit().putBoolean("preMuted", false).apply();
        else restoreVolume(this, true);
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.deleteNotificationChannel("redirect");
        NotificationChannel ch = new NotificationChannel(CHANNEL, "镜像状态",
                NotificationManager.IMPORTANCE_DEFAULT);
        ch.setSound(null, null);
        ch.enableVibration(false);
        ch.setShowBadge(false);
        nm.createNotificationChannel(ch);
        startForeground(2, notif());
        am = getSystemService(AudioManager.class);
        wake =getSystemService(PowerManager.class)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "musicrelay:mirror");
        msm = getSystemService(MediaSessionManager.class);
        try {
            msm.addOnActiveSessionsChangedListener(sessionsChanged,
                    new ComponentName(this, Listener.class));
            bind(msm.getActiveSessions(new ComponentName(this, Listener.class)));
        } catch (SecurityException e) {
            Log.e(TAG, "no notification access", e);
        }
        running = true;
        h.postDelayed(loop, TICK_MS);
    }

    // Clash-Meta-style lifecycle: the service exists only while mirroring is on. Turning it
    // off stops the service and removes the notification; if the OS kills us we stay dead
    // (START_NOT_STICKY) instead of coming back on our own.
    @Override
    public int onStartCommand(Intent i, int f, int id) {
        String a = i == null ? null : i.getAction();
        if (ACTION_RENOTIFY.equals(a)) {
            // Notification was swiped away: re-post it while mirroring; never start mirroring.
            if (mirroring) updateNotif();
            else stopSelf();
            return START_NOT_STICKY;
        }
        if (ACTION_PREV.equals(a) || ACTION_PLAYPAUSE.equals(a) || ACTION_NEXT.equals(a)) {
            if (mirroring) transport(a);
            else stopSelf();
            return START_NOT_STICKY;
        }
        if (ACTION_STOP.equals(a)) setMirroring(false);
        else if (ACTION_TOGGLE.equals(a)) setMirroring(!mirroring);
        else if (!mirroring) setMirroring(true);
        return START_NOT_STICKY;
    }

    private void setMirroring(boolean on) {
        mirroring = on;
        if (on) {
            SharedPreferences p = getSharedPreferences("am", MODE_PRIVATE);
            try {
                int v = am.getStreamVolume(AudioManager.STREAM_MUSIC);
                // If we are already muted (restored after a kill), keep the remembered volume.
                if (v > 0) {
                    savedVol = v;
                    p.edit().putInt("savedVol", v).apply();
                } else {
                    savedVol = p.getInt("savedVol",
                            am.getStreamMaxVolume(AudioManager.STREAM_MUSIC) / 2);
                }
                am.setStreamVolume(AudioManager.STREAM_MUSIC, 0, 0);
                p.edit().putBoolean("mutedByUs", true).apply();
            } catch (Exception ignored) {
            }
            if (!wake.isHeld()) wake.acquire();
            lastActive = SystemClock.elapsedRealtime();
            health = OK;
            lastId = "";
            lastState = -1;
            lastPlayingPos = -1;
            // Wake the player's app (and Music Relay out of standby) before the first song.
            String app = mirroredApp();
            if (app != null) sendTo("open", null, app);
            sync();
        } else {
            // Quit every music app Music Relay started on the player; it then goes to standby.
            sendTo("close", null, null);
            if (savedVol > 0) {
                try {
                    am.setStreamVolume(AudioManager.STREAM_MUSIC, savedVol, 0);
                    getSharedPreferences("am", MODE_PRIVATE).edit()
                            .putBoolean("mutedByUs", false).apply();
                } catch (Exception ignored) {
                }
            }
            if (wake.isHeld()) wake.release();
            health = IDLE;
            curTitle = "";
            curArtist = "";
            writeState();
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf();
            return;
        }
        updateNotif();
    }

    @Override
    public void onDestroy() {
        boolean wasMirroring = mirroring;
        running = false;
        mirroring = false;
        health = IDLE;
        writeState();
        try {
            msm.removeOnActiveSessionsChangedListener(sessionsChanged);
        } catch (Exception ignored) {
        }
        if (current != null) current.unregisterCallback(cb);
        h.removeCallbacksAndMessages(null);
        if (wake != null && wake.isHeld()) wake.release();
        if (wasMirroring && savedVol > 0) {
            try {
                am.setStreamVolume(AudioManager.STREAM_MUSIC, savedVol, 0);
                getSharedPreferences("am", MODE_PRIVATE).edit()
                        .putBoolean("mutedByUs", false).apply();
            } catch (Exception ignored) {
            }
        }
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent i) {
        return null;
    }

    @Override
    public void onTaskRemoved(Intent root) {
        // Like Clash Meta: swiping the UI away leaves mirroring running.
        super.onTaskRemoved(root);
    }

    private void transport(String a) {
        if (current == null) rescan();
        MediaController c = current;
        if (c == null) return;
        MediaController.TransportControls t = c.getTransportControls();
        if (ACTION_PREV.equals(a)) t.skipToPrevious();
        else if (ACTION_NEXT.equals(a)) t.skipToNext();
        else if (curPlaying) t.pause();
        else t.play();
    }

    private void rescan() {
        try {
            bind(msm.getActiveSessions(new ComponentName(this, Listener.class)));
        } catch (Exception ignored) {
        }
    }

    private void bind(List<MediaController> all) {
        // Follow the chosen app; in auto mode prefer the one playing, else stay on the
        // current one while it is still around.
        String src = source(this);
        MediaController found = null, playing = null, same = null;
        if (all != null) {
            for (MediaController c : all) {
                String p = c.getPackageName();
                if (AUTO.equals(src) ? !AgentService.supported(p) : !src.equals(p)) continue;
                if (found == null) found = c;
                PlaybackState st = c.getPlaybackState();
                if (playing == null && st != null && st.getState() == PlaybackState.STATE_PLAYING) {
                    playing = c;
                }
                if (current != null && c.getSessionToken().equals(current.getSessionToken())) same = c;
            }
        }
        if (playing != null) found = playing;
        else if (same != null) found = same;
        // getActiveSessions returns new controller objects each call; compare by session token.
        if (found == current) return;
        if (found != null && current != null
                && found.getSessionToken().equals(current.getSessionToken())) return;
        if (current != null) current.unregisterCallback(cb);
        current = found;
        lastId = "";
        lastState = -1;
        if (current != null) {
            current.registerCallback(cb);
            if (mirroring) sync();
        }
    }

    private static long pos(PlaybackState st) {
        long p = st.getPosition();
        if (st.getState() == PlaybackState.STATE_PLAYING) {
            p += (long) ((SystemClock.elapsedRealtime() - st.getLastPositionUpdateTime())
                    * st.getPlaybackSpeed());
        }
        return p;
    }

    private void sync() {
        MediaController c = current;
        if (c == null) return;
        PlaybackState st = c.getPlaybackState();
        MediaMetadata md = c.getMetadata();
        if (st == null || md == null) return;
        String id = md.getString(MediaMetadata.METADATA_KEY_MEDIA_ID);
        int state = st.getState();
        boolean playingNow = state == PlaybackState.STATE_PLAYING
                || state == PlaybackState.STATE_BUFFERING;
        if (playingNow != curPlaying) {
            curPlaying = playingNow;
            updateNotif();
        }
        if (id != null && !id.isEmpty() && !id.equals(lastId)) {
            lastId = id;
            lastState = state;
            lastPlayingPos = -1;
            String t = md.getString(MediaMetadata.METADATA_KEY_TITLE);
            String ar = md.getString(MediaMetadata.METADATA_KEY_ARTIST);
            curTitle = t == null ? "" : t;
            curArtist = ar == null ? "" : ar;
            send("mediaid", id);
            updateNotif();
            return;
        }
        if (state != lastState) {
            lastState = state;
            if (state == PlaybackState.STATE_PLAYING) send("play", null);
            else if (state == PlaybackState.STATE_PAUSED
                    || state == PlaybackState.STATE_STOPPED) send("pause", null);
        }
        long cur = st.getPosition();
        long now = SystemClock.elapsedRealtime();
        if (state == PlaybackState.STATE_PLAYING) {
            if (lastPlayingPos >= 0) {
                long expected = lastPlayingPos
                        + (long) ((now - lastPlayingWall) * st.getPlaybackSpeed());
                if (Math.abs(cur - expected) > SEEK_JUMP) send("seek", String.valueOf(cur));
            }
            lastPlayingPos = cur;
            lastPlayingWall = now;
        } else {
            lastPlayingPos = -1;
        }
    }

    private void alignProgress() {
        MediaController c = current;
        if (c == null) return;
        PlaybackState st = c.getPlaybackState();
        if (st == null || st.getState() != PlaybackState.STATE_PLAYING) return;
        final long phonePos = pos(st);
        final String app = c.getPackageName();
        io.execute(() -> {
            long tp = tabletPos(app);
            if (tp >= 0 && Math.abs(phonePos - tp) > ALIGN_TOLERANCE) {
                send("seek", String.valueOf(phonePos));
            }
        });
    }

    private boolean paired() {
        return !getSharedPreferences("am", MODE_PRIVATE).getString("host", "").isEmpty();
    }

    private void setHealth(int hh) {
        if (health == hh) return;
        health = hh;
        h.post(this::updateNotif);
    }

    // The app being mirrored: the bound session's, else the chosen source (null in auto mode).
    private String mirroredApp() {
        MediaController c = current;
        if (c != null) return c.getPackageName();
        String s = source(this);
        return AUTO.equals(s) ? null : s;
    }

    private void send(String cmd, String val) {
        // The player runs the command in the same app the phone is playing in.
        String app = mirroredApp();
        sendTo(cmd, val, app == null ? AM : app);
    }

    // app null: the player applies the command to every supported app (used by close).
    private void sendTo(String cmd, String val, String app) {
        if (!paired()) {
            setHealth(ERROR);
            return;
        }
        io.execute(() -> {
            try {
                String u = "/cmd?c=" + cmd + (app == null ? "" : "&app=" + app)
                        + (val == null ? "" : "&v=" + URLEncoder.encode(val, "UTF-8"));
                HttpURLConnection h2 = Tls.open(this, u);
                h2.setConnectTimeout(3000);
                h2.setReadTimeout(8000);
                int rc = h2.getResponseCode();
                h2.disconnect();
                Log.i(TAG, cmd + " " + val + " -> " + rc);
                setHealth(rc == 200 ? OK : ERROR);
            } catch (Exception e) {
                Log.w(TAG, cmd + " failed", e);
                setHealth(ERROR);
            }
        });
    }

    private boolean probe() {
        if (!paired()) return false;
        try {
            HttpURLConnection h2 = Tls.open(this, "/state");
            h2.setConnectTimeout(3000);
            h2.setReadTimeout(6000);
            int rc = h2.getResponseCode();
            h2.disconnect();
            return rc == 200;
        } catch (Exception e) {
            Log.w(TAG, "probe failed", e);
            return false;
        }
    }

    private long tabletPos(String app) {
        if (!paired()) return -1;
        try {
            HttpURLConnection h2 = Tls.open(this, "/state?app=" + app);
            h2.setConnectTimeout(3000);
            h2.setReadTimeout(6000);
            if (h2.getResponseCode() != 200) {
                h2.disconnect();
                return -1;
            }
            InputStream in = h2.getInputStream();
            java.io.ByteArrayOutputStream o = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) o.write(buf, 0, n);
            h2.disconnect();
            JSONObject j = new JSONObject(o.toString("UTF-8"));
            if (j.optInt("state", 0) != PlaybackState.STATE_PLAYING) return -1;
            return j.optLong("pos", -1);
        } catch (Exception e) {
            return -1;
        }
    }

    private Notification notif() {
        int color;
        String title, text;
        // vivo draws every notification grey and ignores setColor, so the state is also
        // carried by a colored dot that renders on any system.
        if (!mirroring) {
            color = 0xFFB4B7BD;
            title = "⚪ Music Relay · 未镜像";
            text = "点按进入 App，或用下拉开关开启镜像";
        } else if (health == ERROR) {
            color = 0xFFFF3B30;
            title = "🔴 同步错误";
            text = "播放器连不上，检查网络或播放器是否运行";
        } else {
            color = 0xFF30D158;
            String song = curTitle.isEmpty() ? "镜像中" : curTitle
                    + (curArtist.isEmpty() ? "" : " - " + curArtist);
            title = "🟢 " + song;
            MediaController c = current;
            String app = c == null ? "" : AgentService.label(c.getPackageName()) + " ";
            text = curPlaying ? "▶ " + app + "镜像中 · 播放器播放中"
                    : "❚❚ " + app + "镜像中 · 已暂停";
        }
        return new Notification.Builder(this, CHANNEL)
                .setOnlyAlertOnce(true)
                .setContentTitle(title)
                .setContentText(text)
                .setContentIntent(PendingIntent.getActivity(this, 0,
                        new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        PendingIntent.FLAG_IMMUTABLE))
                .setSmallIcon(R.drawable.ic_stat)
                .setLargeIcon(dot(color))
                // Android 14+ lets users swipe away FGS notifications; put it straight back.
                .setDeleteIntent(PendingIntent.getService(this, 2,
                        new Intent(this, RedirectService.class).setAction(ACTION_RENOTIFY),
                        PendingIntent.FLAG_IMMUTABLE))
                .addAction(action(R.drawable.ic_prev, "上一曲", ACTION_PREV))
                .addAction(curPlaying ? action(R.drawable.ic_pause, "暂停", ACTION_PLAYPAUSE)
                        : action(R.drawable.ic_play, "播放", ACTION_PLAYPAUSE))
                .addAction(action(R.drawable.ic_next, "下一曲", ACTION_NEXT))
                .addAction(action(R.drawable.ic_stop, "停止镜像", ACTION_STOP))
                .setStyle(new Notification.MediaStyle().setShowActionsInCompactView(0, 1, 2))
                .setColorized(true).setColor(color)
                .setOngoing(true).setShowWhen(false).build();
    }

    private Notification.Action action(int icon, String label, String act) {
        return new Notification.Action.Builder(android.graphics.drawable.Icon.createWithResource(this, icon),
                label, PendingIntent.getService(this, act.hashCode(),
                        new Intent(this, RedirectService.class).setAction(act),
                        PendingIntent.FLAG_IMMUTABLE)).build();
    }

    // A solid colored disc with a white music glyph, used as the large icon. Unlike
    // setColor, a bitmap keeps its colors on every launcher, including vivo's.
    private static android.graphics.Bitmap dot(int color) {
        int s = 192;
        android.graphics.Bitmap b = android.graphics.Bitmap.createBitmap(s, s,
                android.graphics.Bitmap.Config.ARGB_8888);
        android.graphics.Canvas c = new android.graphics.Canvas(b);
        android.graphics.Paint p = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        p.setColor(color);
        c.drawCircle(s / 2f, s / 2f, s / 2f, p);
        p.setColor(0xFFFFFFFF);
        p.setTextSize(s * 0.5f);
        p.setTextAlign(android.graphics.Paint.Align.CENTER);
        c.drawText("♫", s / 2f, s / 2f - (p.descent() + p.ascent()) / 2f, p);
        return b;
    }

    private void updateNotif() {
        writeState();
        shownHealth = mirroring ? health : IDLE;
        try {
            getSystemService(NotificationManager.class).notify(2, notif());
        } catch (Exception ignored) {
        }
    }
}
