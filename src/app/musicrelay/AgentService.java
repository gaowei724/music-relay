package app.musicrelay;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSession;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.net.Uri;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.os.IBinder;
import android.os.PowerManager;
import android.os.SystemClock;
import android.util.Log;
import android.view.KeyEvent;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONObject;

public class AgentService extends Service {
    static final String TAG = "MusicRelay";
    static final String AM = "com.apple.android.music";
    static final int PORT = 8765;
    static volatile String lastError = "";
    static volatile boolean up;

    // The user's on/off choice for the agent (tile / app), kept across reboot and update.
    static boolean enabled(Context c) {
        return c.getSharedPreferences("am", MODE_PRIVATE).getBoolean("agentOn", true);
    }

    static void setEnabled(Context c, boolean on) {
        c.getSharedPreferences("am", MODE_PRIVATE).edit().putBoolean("agentOn", on).apply();
    }

    private ServerSocket server;
    private volatile boolean running;
    private PowerManager.WakeLock wake;
    private WifiManager.WifiLock wifi;
    private String token;

    static String token(Context c) {
        android.content.SharedPreferences p = c.getSharedPreferences("am", MODE_PRIVATE);
        String t = p.getString("token", null);
        // Four digits are easy to type; guessing is throttled in handle().
        if (t == null || t.length() != 4) {
            t = String.format(Locale.US, "%04d", new SecureRandom().nextInt(10000));
            p.edit().putString("token", t).apply();
        }
        return t;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        NotificationManager nm = getSystemService(NotificationManager.class);
        // Low-importance channels are folded into "silent notifications" on Samsung and
        // get no status-bar icon, so use a default-importance channel without sound.
        nm.deleteNotificationChannel("agent");
        NotificationChannel ch = new NotificationChannel(STATUS_CHANNEL, "播放器状态",
                NotificationManager.IMPORTANCE_DEFAULT);
        ch.setSound(null, null);
        ch.enableVibration(false);
        ch.setShowBadge(false);
        nm.createNotificationChannel(ch);
        startForeground(1, statusNotif());
        wake = getSystemService(PowerManager.class)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "musicrelay:agent");
        wifi = getSystemService(WifiManager.class)
                .createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "musicrelay:agent");
        lastActive = SystemClock.elapsedRealtime();
        token = token(this);
        running = true;
        up = true;
        new Thread(this::serve, "http").start();
        tick.run();
    }

    static final String STATUS_CHANNEL = "agent_status";
    static final long IDLE_GRACE_MS = 15 * 60 * 1000; // keep awake this long after music stops
    private long lastActive;
    private String shown = "";
    private final android.os.Handler ticker = new android.os.Handler(android.os.Looper.getMainLooper());

    // Every 15 s: follow Apple Music. While it plays (and for a grace period afterwards)
    // hold the CPU and Wi-Fi awake; once it has been silent for long, release them and
    // let the system save power, like Apple Music itself would be.
    private final Runnable tick = new Runnable() {
        @Override public void run() {
            try {
                MediaController c = session();
                PlaybackState st = c == null ? null : c.getPlaybackState();
                if (st != null && st.getState() == PlaybackState.STATE_PLAYING) {
                    lastActive = SystemClock.elapsedRealtime();
                }
                holdLocks(SystemClock.elapsedRealtime() - lastActive < IDLE_GRACE_MS);
                refreshNotif();
            } catch (Exception e) {
                Log.w(TAG, "tick", e);
            } finally {
                ticker.postDelayed(this, 3000);
            }
        }
    };

    private void holdLocks(boolean on) {
        if (on) {
            if (!wake.isHeld()) wake.acquire();
            if (!wifi.isHeld()) wifi.acquire();
        } else {
            if (wake.isHeld()) wake.release();
            if (wifi.isHeld()) wifi.release();
        }
    }

    private Notification statusNotif() {
        MediaController c = null;
        try {
            c = session();
        } catch (Exception ignored) {
        }
        PlaybackState st = c == null ? null : c.getPlaybackState();
        MediaMetadata md = c == null ? null : c.getMetadata();
        int s = st == null ? PlaybackState.STATE_NONE : st.getState();
        boolean playing = s == PlaybackState.STATE_PLAYING || s == PlaybackState.STATE_BUFFERING;
        String song = md == null ? "" : str(md, MediaMetadata.METADATA_KEY_TITLE);
        String artist = md == null ? "" : str(md, MediaMetadata.METADATA_KEY_ARTIST);
        String full = song + (artist.isEmpty() ? "" : " - " + artist);
        String title, text;
        String link = Tls.serverSecure(this) ? "加密连接" : "明文连接";
        if (playing) {
            title = "🟢 " + full;
            text = "▶ 播放中 · " + link;
        } else if (standby) {
            title = "⚪ Music Relay 播放器 · 待命";
            text = "手机开启镜像时自动唤醒";
        } else if (!song.isEmpty()) {
            title = "⏸ " + full;
            text = "❚❚ 已暂停 · " + link;
        } else {
            title = "⚪ Music Relay 播放器 · 待命";
            text = "等待手机控制 · " + link;
        }
        return new Notification.Builder(this, STATUS_CHANNEL)
                .setContentTitle(title)
                .setContentText(text)
                .setContentIntent(openSelf())
                // Android 14+ lets users swipe away FGS notifications; put it straight back.
                .setDeleteIntent(PendingIntent.getService(this, 2,
                        new Intent(this, AgentService.class).setAction(ACTION_RENOTIFY),
                        PendingIntent.FLAG_IMMUTABLE))
                .setSmallIcon(R.drawable.ic_stat)
                .setColor(playing ? 0xFF30D158 : 0xFFB4B7BD)
                .addAction(action(R.drawable.ic_prev, "上一曲", ACTION_PREV))
                .addAction(playing ? action(R.drawable.ic_pause, "暂停", ACTION_PAUSE)
                        : action(R.drawable.ic_play, "播放", ACTION_PLAY))
                .addAction(action(R.drawable.ic_next, "下一曲", ACTION_NEXT))
                .setStyle(new Notification.MediaStyle().setShowActionsInCompactView(0, 1, 2))
                .setOnlyAlertOnce(true)
                .setShowWhen(false)
                .setOngoing(true).build();
    }

    private Notification.Action action(int icon, String label, String act) {
        return new Notification.Action.Builder(android.graphics.drawable.Icon.createWithResource(this, icon),
                label, PendingIntent.getService(this, act.hashCode(),
                        new Intent(this, AgentService.class).setAction(act),
                        PendingIntent.FLAG_IMMUTABLE)).build();
    }

    // Notification buttons act on the music app playing here.
    private void transport(String act) {
        MediaController c = session();
        if (c == null) return;
        MediaController.TransportControls t = c.getTransportControls();
        if (ACTION_PREV.equals(act)) t.skipToPrevious();
        else if (ACTION_NEXT.equals(act)) t.skipToNext();
        else if (ACTION_PLAY.equals(act)) t.play();
        else t.pause();
        ticker.postDelayed(() -> {
            shown = "";
            refreshNotif();
        }, 600);
    }

    private void refreshNotif() {
        Notification n = statusNotif();
        String key = n.extras.getCharSequence(Notification.EXTRA_TITLE) + "|"
                + n.extras.getCharSequence(Notification.EXTRA_TEXT);
        if (key.equals(shown)) return;
        shown = key;
        getSystemService(NotificationManager.class).notify(1, n);
    }

    static final String ACTION_RENOTIFY = "app.musicrelay.AGENT_RENOTIFY";
    static final String ACTION_PREV = "app.musicrelay.AGENT_PREV";
    static final String ACTION_PLAY = "app.musicrelay.AGENT_PLAY";
    static final String ACTION_PAUSE = "app.musicrelay.AGENT_PAUSE";
    static final String ACTION_NEXT = "app.musicrelay.AGENT_NEXT";

    @Override
    public int onStartCommand(Intent i, int f, int id) {
        if (i != null && ACTION_RENOTIFY.equals(i.getAction())) {
            if (!enabled(this)) {
                stopSelf();
                return START_NOT_STICKY;
            }
            shown = "";
            refreshNotif();
        } else if (i != null && i.getAction() != null && i.getAction().startsWith("app.musicrelay.AGENT_")) {
            try {
                transport(i.getAction());
            } catch (Exception e) {
                Log.w(TAG, "transport", e);
            }
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        running = false;
        up = false;
        ticker.removeCallbacksAndMessages(null);
        try {
            if (server != null) server.close();
        } catch (Exception ignored) {
        }
        if (wake.isHeld()) wake.release();
        if (wifi.isHeld()) wifi.release();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent i) {
        return null;
    }

    // Keep running after the app is swiped out of recents (VPN-like persistence).
    @Override
    public void onTaskRemoved(Intent root) {
        try {
            if (enabled(this)) startForegroundService(new Intent(this, AgentService.class));
        } catch (Exception ignored) {
        }
        super.onTaskRemoved(root);
    }

    private PendingIntent openSelf() {
        return PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_IMMUTABLE);
    }

    private void serve() {
        try {
            javax.net.ssl.SSLServerSocketFactory f = Tls.serverFactory(this);
            server = f == null ? new ServerSocket(PORT) : f.createServerSocket(PORT);
            while (running) {
                Socket s = server.accept();
                new Thread(() -> handle(s), "req").start();
            }
        } catch (Exception e) {
            if (running) {
                lastError = e.toString();
                Log.e(TAG, "server", e);
            }
        }
    }

    private void handle(Socket s) {
        try (Socket sock = s) {
            sock.setSoTimeout(15000);
            BufferedReader r = new BufferedReader(
                    new InputStreamReader(sock.getInputStream(), StandardCharsets.UTF_8));
            String line = r.readLine();
            if (line == null) return;
            String[] parts = line.split(" ");
            String auth = "";
            String h;
            while ((h = r.readLine()) != null && !h.isEmpty()) {
                if (h.toLowerCase(Locale.US).startsWith("x-token:")) auth = h.substring(8).trim();
            }
            String target = parts.length > 1 ? parts[1] : "/";
            int q = target.indexOf('?');
            String path = q < 0 ? target : target.substring(0, q);
            Map<String, String> args = parse(q < 0 ? "" : target.substring(q + 1));
            OutputStream out = sock.getOutputStream();
            String ip = sock.getInetAddress().getHostAddress();
            if (lockedOut(ip)) {
                send(out, 429, "application/json", "{\"error\":\"too many attempts\"}".getBytes());
                return;
            }
            if (!token.equals(auth)) {
                failed(ip);
                send(out, 401, "application/json", "{\"error\":\"unauthorized\"}".getBytes());
                return;
            }
            if (args.containsKey("app")) args.put("app", playerApp(args.get("app")));
            if (path.equals("/art")) {
                byte[] png = art(args.get("app"));
                if (png == null) send(out, 404, "text/plain", new byte[0]);
                else send(out, 200, "image/png", png);
                return;
            }
            JSONObject res = route(path, args);
            send(out, 200, "application/json; charset=utf-8",
                    res.toString().getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            Log.w(TAG, "request", e);
        }
    }

    // A 4-digit code can be guessed, so an address that gets it wrong 5 times is refused
    // for a minute. A correct code still has to come from an address that is not locked.
    private final Map<String, long[]> fails = new HashMap<>();

    private synchronized boolean lockedOut(String ip) {
        long[] f = fails.get(ip);
        return f != null && f[0] >= 5 && System.currentTimeMillis() - f[1] < 60000;
    }

    private synchronized void failed(String ip) {
        long now = System.currentTimeMillis();
        long[] f = fails.get(ip);
        if (f == null || now - f[1] >= 60000) f = new long[] {0, now};
        f[0]++;
        f[1] = now;
        fails.put(ip, f);
    }

    private static Map<String, String> parse(String q) throws Exception {
        Map<String, String> m = new HashMap<>();
        for (String kv : q.split("&")) {
            int e = kv.indexOf('=');
            if (e > 0) m.put(kv.substring(0, e), URLDecoder.decode(kv.substring(e + 1), "UTF-8"));
        }
        return m;
    }

    private static void send(OutputStream o, int code, String type, byte[] body) throws Exception {
        String head = "HTTP/1.1 " + code + " X\r\nContent-Type: " + type
                + "\r\nContent-Length: " + body.length + "\r\nConnection: close\r\n\r\n";
        o.write(head.getBytes(StandardCharsets.US_ASCII));
        o.write(body);
        o.flush();
    }

    private JSONObject route(String path, Map<String, String> a) throws Exception {
        switch (path) {
            case "/state":
                return state(a.get("app"));
            case "/cmd":
                return command(a.get("c"), a.get("v"), a.get("app"));
            case "/src":
                return src();
            default:
                return new JSONObject().put("error", "not found");
        }
    }

    // SRC report: compares what the app sends with what the USB output actually runs at,
    // and explains why it cannot be bypassed. Detection only, nothing is changed.
    private JSONObject src() throws Exception {
        JSONObject o = new JSONObject();
        JSONArray why = new JSONArray();
        android.media.AudioManager am = getSystemService(android.media.AudioManager.class);
        android.media.AudioDeviceInfo usb = null;
        for (android.media.AudioDeviceInfo d : am.getDevices(android.media.AudioManager.GET_DEVICES_OUTPUTS)) {
            int t = d.getType();
            if (t == android.media.AudioDeviceInfo.TYPE_USB_DEVICE
                    || t == android.media.AudioDeviceInfo.TYPE_USB_HEADSET) usb = d;
        }
        o.put("usb", usb != null);
        if (usb == null) {
            o.put("verdict", "未检测到 USB DAC");
            why.put("当前输出不是 USB 设备，SRC 检测只针对 USB DAC。");
            return o.put("why", why);
        }
        o.put("device", String.valueOf(usb.getProductName()));
        JSONArray rates = new JSONArray();
        int[] supported = usb.getSampleRates();
        java.util.Arrays.sort(supported);
        for (int r : supported) rates.put(r);
        o.put("rates", rates);
        boolean bpSupported = false;
        if (android.os.Build.VERSION.SDK_INT >= 34) {
            for (android.media.AudioMixerAttributes m : am.getSupportedMixerAttributes(usb)) {
                if (m.getMixerBehavior() == android.media.AudioMixerAttributes.MIXER_BEHAVIOR_BIT_PERFECT) {
                    bpSupported = true;
                }
            }
        }
        o.put("bitPerfectSupported", bpSupported);

        boolean dump = checkSelfPermission(android.Manifest.permission.DUMP)
                == android.content.pm.PackageManager.PERMISSION_GRANTED;
        o.put("dump", dump);
        JSONObject track = null;
        int outRate = 0;
        String outFormat = "";
        if (dump) {
            Process p = Runtime.getRuntime().exec(new String[] {"dumpsys", "media.audio_flinger"});
            BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()));
            boolean standby = true, isUsb = false;
            int rate = 0;
            String fmt = "";
            java.util.regex.Pattern row = java.util.regex.Pattern.compile(
                    "^\\s*[A-Z]?\\s*\\d+\\s+yes\\s+\\d+/\\s*(\\d+)\\s+\\d+\\s+\\d+\\s+\\S\\s+0x\\S+\\s+\\S+\\s+\\S+\\s+(\\d+)\\s");
            for (String line; (line = r.readLine()) != null; ) {
                String t = line.trim();
                if (t.startsWith("Output thread")) {
                    standby = true; isUsb = false; rate = 0; fmt = "";
                } else if (t.startsWith("Standby:")) {
                    standby = t.contains("yes");
                } else if (t.startsWith("Sample rate:")) {
                    rate = Integer.parseInt(t.replaceAll("\\D", ""));
                } else if (t.startsWith("HAL format:")) {
                    // e.g. AUDIO_FORMAT_PCM_8_24_BIT is 24-bit audio in 32-bit words.
                    fmt = t.replaceAll(".*AUDIO_FORMAT_PCM_(8_)?", "").replace("_BIT)", "")
                            .replace(")", "") + " bit";
                } else if (t.startsWith("Output devices:")) {
                    isUsb = t.contains("USB");
                } else if (!standby && isUsb && track == null) {
                    java.util.regex.Matcher m = row.matcher(line);
                    if (!m.find()) continue;
                    int uid = Integer.parseInt(m.group(1));
                    if (uid == 1000) continue; // system sounds
                    // Booleans in a track row: PortMuted, BitPerfect, InternalMute.
                    List<String> flags = new java.util.ArrayList<>();
                    for (String tok : t.split("\\s+")) {
                        if (tok.equals("true") || tok.equals("false")) flags.add(tok);
                    }
                    String[] pkgs = getPackageManager().getPackagesForUid(uid);
                    track = new JSONObject()
                            .put("app", appLabel(pkgs == null ? null : pkgs[0]))
                            .put("rate", Integer.parseInt(m.group(2)))
                            .put("bitPerfect", "true".equals(flags.size() >= 3 ? flags.get(1)
                                    : flags.isEmpty() ? "" : flags.get(0)));
                    outRate = rate;
                    outFormat = fmt;
                }
            }
            p.waitFor();
        }
        if (track != null) {
            o.put("track", track).put("outRate", outRate).put("outFormat", outFormat);
        }

        // Verdict
        String khz = track == null ? "" : khz(track.getInt("rate"));
        boolean resampled = track != null && track.getInt("rate") != outRate;
        boolean trackBp = track != null && track.getBoolean("bitPerfect");
        if (!dump) {
            o.put("verdict", "无法读取实际输出");
            why.put("需要一次性 adb 授权：adb shell pm grant " + getPackageName() + " android.permission.DUMP");
        } else if (track == null) {
            o.put("verdict", "没有正在播放的音轨");
            why.put("请在播放器上播放音乐后再检测。");
        } else if (trackBp) {
            o.put("verdict", "无 SRC · bit-perfect");
        } else if (resampled) {
            o.put("verdict", "有 SRC：" + khz + " → " + khz(outRate));
        } else {
            o.put("verdict", "采样率一致（" + khz + "），但经过系统混音器");
            why.put("没有重采样，但数据仍经过 Android 混音器（音量、混音），不是 bit-perfect。");
        }
        if (track != null && resampled) {
            boolean listed = false;
            for (int i = 0; i < rates.length(); i++) if (rates.getInt(i) == track.getInt("rate")) listed = true;
            if (rates.length() > 0 && !listed) {
                why.put("系统 USB 音频驱动没给这台 DAC 开放 " + khz + " 输出，只有 "
                        + rateList(rates) + "，所以只能重采样。");
            } else {
                why.put("Android 混音器当前锁在 " + khz(outRate)
                        + "，所有 App 共用这一个输出流，不会按曲目切换采样率。");
            }
        }
        if (!trackBp) {
            if (!bpSupported) {
                why.put("系统不提供 bit-perfect（USB 独占）模式：Android 14 的这个功能需要 AIDL 音频 HAL，"
                        + "本机为这台 DAC 报告的 bit-perfect 混音属性为空。");
            } else {
                why.put("系统支持 bit-perfect，但播放 App 没有使用（App 要自己调用 setPreferredMixerAttributes）。");
            }
        }
        boolean bypass = trackBp || bpSupported;
        o.put("canBypass", bypass);
        why.put(bypass
                ? "结论：可以绕过，需要播放 App 请求 bit-perfect 输出（Apple Music 目前不会）。"
                : "结论：不能绕过。改 audio policy 或 HAL 需要 root / 系统签名，普通 App 做不到；"
                        + "要无 SRC 只能换支持 AIDL HAL 的设备，或用自带 USB 驱动的播放器（如 UAPP）。");
        return o.put("why", why);
    }

    private String appLabel(String pkg) {
        if (pkg == null) return "未知 App";
        try {
            return String.valueOf(getPackageManager().getApplicationLabel(
                    getPackageManager().getApplicationInfo(pkg.split(":")[0], 0)));
        } catch (Exception e) {
            return pkg;
        }
    }

    private static String khz(int hz) {
        return (hz % 1000 == 0 ? String.valueOf(hz / 1000) : String.format(Locale.US, "%.1f", hz / 1000f)) + " kHz";
    }

    private static String rateList(JSONArray rates) throws Exception {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < rates.length(); i++) {
            if (i > 0) b.append("/");
            b.append(khz(rates.getInt(i)).replace(" kHz", ""));
        }
        return b + " kHz";
    }

    static final String QQ = "com.tencent.qqmusic";
    static final String NETEASE = "com.netease.cloudmusic";
    // QQ音乐HD: not supported (its media ids are local and it cannot play a song by id).
    static final String QQ_HD = "com.tencent.qqmusicpad";
    // Apple Music Classical uses the Apple Music catalog (its media ids are Apple Music song
    // ids), so the player plays its songs in Apple Music.
    static final String CLASSICAL = "com.apple.android.music.classical";
    static final String[] APPS = {AM, CLASSICAL, QQ, NETEASE};

    static boolean supported(String pkg) {
        for (String a : APPS) if (a.equals(pkg)) return true;
        return false;
    }

    // The app on the player that plays what the phone's app plays.
    static String playerApp(String pkg) {
        return CLASSICAL.equals(pkg) ? AM : pkg;
    }

    static String label(String pkg) {
        return QQ.equals(pkg) ? "QQ音乐" : NETEASE.equals(pkg) ? "网易云音乐"
                : CLASSICAL.equals(pkg) ? "Apple Music 古典乐" : "Apple Music";
    }

    private MediaController session() {
        return session(null);
    }

    // The session of the given app; with no app, the supported app that is playing, else the
    // most recently active one (getActiveSessions is ordered by priority).
    private MediaController session(String app) {
        MediaSessionManager m = getSystemService(MediaSessionManager.class);
        List<MediaController> all = m.getActiveSessions(new ComponentName(this, Listener.class));
        MediaController first = null;
        for (MediaController c : all) {
            String p = c.getPackageName();
            if (app != null ? !app.equals(p) : !supported(p)) continue;
            if (app != null) return c;
            PlaybackState st = c.getPlaybackState();
            if (st != null && st.getState() == PlaybackState.STATE_PLAYING) return c;
            if (first == null) first = c;
        }
        return first;
    }

    // Only one app plays on the player: pause the others when the phone switches apps.
    private void pauseOthers(String keep) {
        for (String a : APPS) {
            if (a.equals(keep)) continue;
            MediaController c = session(a);
            PlaybackState st = c == null ? null : c.getPlaybackState();
            if (st != null && st.getState() == PlaybackState.STATE_PLAYING) {
                c.getTransportControls().pause();
            }
        }
    }

    // The app in front on the player, from usage events (needs usage access); null if unknown.
    private String foreground() {
        try {
            android.app.usage.UsageStatsManager u =
                    getSystemService(android.app.usage.UsageStatsManager.class);
            long now = System.currentTimeMillis();
            android.app.usage.UsageEvents ev = u.queryEvents(now - 24 * 3600 * 1000L, now);
            android.app.usage.UsageEvents.Event e = new android.app.usage.UsageEvents.Event();
            String fg = null;
            while (ev.hasNextEvent()) {
                ev.getNextEvent(e);
                if (e.getEventType() == android.app.usage.UsageEvents.Event.ACTIVITY_RESUMED) {
                    fg = e.getPackageName();
                }
            }
            return fg;
        } catch (Exception e) {
            return null;
        }
    }

    private String launcher() {
        android.content.pm.ResolveInfo r = getPackageManager().resolveActivity(
                new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0);
        return r == null ? null : r.activityInfo.packageName;
    }

    // Puts back whatever was in front (home screen or another app) after a song link
    // pulled a music app forward.
    private void restoreFront(String fg) {
        if (fg == null || supported(fg)) return;
        Intent i = fg.equals(launcher())
                ? new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
                : getPackageManager().getLaunchIntentForPackage(fg);
        if (i == null) return;
        try {
            startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (Exception e) {
            Log.w(TAG, "restore front", e);
        }
    }

    // Shows the given music app, but only when a music app is already in front: the player
    // screen follows the phone's app without covering the home screen or other apps.
    private void frontIfMusic(String app) {
        String fg = foreground();
        if (fg == null || !supported(fg) || fg.equals(app)) return;
        Intent i = getPackageManager().getLaunchIntentForPackage(app);
        if (i != null) startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    }

    // Phone switched mirroring on: get the chosen app running so the first song starts fast.
    private JSONObject open(String app) throws Exception {
        if (app == null) return new JSONObject().put("ok", true);
        standby = false;
        pauseOthers(app);
        summoned(app);
        if (AM.equals(app)) {
            wake();
            frontIfMusic(AM);
        } else if (session(app) == null) {
            String fg = foreground();
            Intent i = getPackageManager().getLaunchIntentForPackage(app);
            if (i == null) return new JSONObject().put("error", "播放器上没有安装" + label(app));
            startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            if (!supported(fg)) ticker.postDelayed(() -> restoreFront(fg), 4000);
        } else {
            frontIfMusic(app);
        }
        return new JSONObject().put("ok", true);
    }

    // Music apps Music Relay started on the player, so mirroring off can quit all of them.
    // Kept in prefs so a restart of this service does not forget them.
    private void summoned(String app) {
        android.content.SharedPreferences p = getSharedPreferences("am", MODE_PRIVATE);
        String s = p.getString("summoned", "");
        if (!("," + s + ",").contains("," + app + ",")) {
            p.edit().putString("summoned", s.isEmpty() ? app : s + "," + app).apply();
        }
    }

    // Phone switched mirroring off: quit the music apps and go to standby. With no app given,
    // every app Music Relay started (and any supported app still holding a session) is quit.
    // Music Relay itself keeps listening, otherwise the phone could not wake it again.
    private JSONObject close(String app) throws Exception {
        String fg = foreground();
        android.app.ActivityManager am = getSystemService(android.app.ActivityManager.class);
        android.content.SharedPreferences p = getSharedPreferences("am", MODE_PRIVATE);
        String started = "," + p.getString("summoned", "") + ",";
        for (String a : APPS) {
            if (app != null ? !a.equals(app)
                    : !started.contains("," + a + ",") && session(a) == null) continue;
            MediaController c = session(a);
            if (c != null) {
                c.getTransportControls().pause();
                c.getTransportControls().stop();
            }
            if (a.equals(fg)) {
                startActivity(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            }
            // Only works once the app has left the foreground and dropped its media service,
            // which can take a few seconds after stop(); try twice.
            ticker.postDelayed(() -> am.killBackgroundProcesses(a), 3000);
            ticker.postDelayed(() -> am.killBackgroundProcesses(a), 10000);
        }
        if (app == null) p.edit().remove("summoned").apply();
        standby = true;
        lastActive = 0;
        ticker.post(() -> holdLocks(false));
        return new JSONObject().put("ok", true);
    }

    static volatile boolean standby;
    private volatile int linkSeq;

    // QQ Music and NetEase have no play-by-id in their media sessions, but their deep links
    // play a catalog song id. Opening the link brings the app to the front; starting it from
    // the background needs "display over other apps" for this app.
    private JSONObject playLink(String app, String id) throws Exception {
        if (id == null || !id.matches("\\d+")) return new JSONObject().put("error", "bad id");
        String uri = QQ.equals(app)
                ? "qqmusic://qq.com/media/playSonglist?p=" + URLEncoder.encode(
                        "{\"song\":[{\"type\":\"0\",\"songid\":\"" + id + "\"}],\"action\":\"play\"}", "UTF-8")
                : "orpheus://song/" + id;
        pauseOthers(app);
        standby = false;
        summoned(app);
        int seq = ++linkSeq;
        String fg = foreground();
        new Thread(() -> {
            followLink(app, id, uri, seq);
            // The link pulled the app forward; if no music app was in front, put the screen back.
            if (seq == linkSeq && !supported(fg)) ticker.post(() -> restoreFront(fg));
        }, "link").start();
        return new JSONObject().put("ok", true);
    }

    // After a cold start the apps only open their home page (NetEase) or load the song paused
    // behind a splash ad (QQ), so watch the session and resend / press play until it runs.
    private void followLink(String app, String id, String uri, int seq) {
        long start = SystemClock.elapsedRealtime();
        long lastOpen = 0, lastPlay = 0;
        int opens = 0;
        while (seq == linkSeq && SystemClock.elapsedRealtime() - start < 25000) {
            long now = SystemClock.elapsedRealtime();
            MediaController c = session(app);
            MediaMetadata md = c == null ? null : c.getMetadata();
            PlaybackState st = c == null ? null : c.getPlaybackState();
            boolean onSong = md != null && id.equals(md.getString(MediaMetadata.METADATA_KEY_MEDIA_ID));
            if (onSong && st != null && st.getState() == PlaybackState.STATE_PLAYING) return;
            if (onSong && now - lastPlay > 3000 && now - lastOpen > 2000) {
                c.getTransportControls().play();
                lastPlay = now;
            } else if (!onSong && opens < 3 && (opens == 0 || now - lastOpen > 7000)) {
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(uri)).setPackage(app)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                    lastError = "";
                } catch (Exception e) {
                    lastError = "无法打开" + label(app) + "：" + e.getMessage();
                    Log.w(TAG, "link", e);
                    return;
                }
                lastOpen = now;
                opens++;
            }
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                return;
            }
        }
    }

    // Starts Apple Music when it has no session. It resumes the last track until a new one is set.
    private MediaController wake() throws Exception {
        MediaController c = session(AM);
        summoned(AM);
        if (c != null) return c;
        Intent b = new Intent(Intent.ACTION_MEDIA_BUTTON);
        b.setComponent(new ComponentName(AM, AM + ".player.MediaPlaybackService"));
        b.putExtra(Intent.EXTRA_KEY_EVENT,
                new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PLAY));
        startForegroundService(b);
        for (int i = 0; i < 40 && c == null; i++) {
            Thread.sleep(250);
            c = session(AM);
        }
        return c;
    }

    private JSONObject state(String app) throws Exception {
        JSONObject o = new JSONObject();
        MediaController c = session(app);
        o.put("alive", c != null);
        o.put("app", c != null ? c.getPackageName() : app != null ? app : AM);
        android.media.AudioManager am = getSystemService(android.media.AudioManager.class);
        o.put("vol", am.getStreamVolume(android.media.AudioManager.STREAM_MUSIC))
                .put("volMax", am.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC));
        if (c == null) return o;
        PlaybackState st = c.getPlaybackState();
        MediaMetadata md = c.getMetadata();
        if (st != null) {
            long pos = st.getPosition();
            if (st.getState() == PlaybackState.STATE_PLAYING) {
                pos += (long) ((SystemClock.elapsedRealtime() - st.getLastPositionUpdateTime())
                        * st.getPlaybackSpeed());
            }
            o.put("state", st.getState()).put("pos", pos).put("activeId", st.getActiveQueueItemId());
        }
        if (md != null) {
            o.put("title", str(md, MediaMetadata.METADATA_KEY_TITLE))
                    .put("artist", str(md, MediaMetadata.METADATA_KEY_ARTIST))
                    .put("album", str(md, MediaMetadata.METADATA_KEY_ALBUM))
                    .put("duration", md.getLong(MediaMetadata.METADATA_KEY_DURATION));
        }
        JSONArray q = new JSONArray();
        List<MediaSession.QueueItem> items = c.getQueue();
        if (items != null) {
            for (MediaSession.QueueItem i : items) {
                q.put(new JSONObject().put("id", i.getQueueId())
                        .put("title", String.valueOf(i.getDescription().getTitle()))
                        .put("artist", String.valueOf(i.getDescription().getSubtitle())));
            }
        }
        return o.put("queue", q);
    }

    private static String str(MediaMetadata m, String k) {
        String s = m.getString(k);
        return s == null ? "" : s;
    }

    // app: the music app the phone is mirroring (null = Apple Music for play-by-id, else
    // whichever supported app is active).
    private JSONObject command(String c, String v, String app) throws Exception {
        if (app != null && !supported(app)) return new JSONObject().put("error", "unsupported app");
        // A command from the phone counts as activity: stay awake for the grace period.
        lastActive = SystemClock.elapsedRealtime();
        ticker.post(() -> holdLocks(true));
        // Media volume of the tablet (digital attenuation; normally kept at max).
        if ("vol".equals(c) || "volmax".equals(c)) {
            android.media.AudioManager am = getSystemService(android.media.AudioManager.class);
            int max = am.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC);
            int to = "volmax".equals(c) ? max : Math.max(0, Math.min(max, Integer.parseInt(v)));
            am.setStreamVolume(android.media.AudioManager.STREAM_MUSIC, to, 0);
            return new JSONObject().put("ok", true).put("vol",
                    am.getStreamVolume(android.media.AudioManager.STREAM_MUSIC));
        }
        if ("open".equals(c)) return open(app);
        if ("close".equals(c)) return close(app);
        boolean byId = "mediaid".equals(c) || "uri".equals(c);
        boolean isAm = app == null || AM.equals(app);
        if ("mediaid".equals(c) && !isAm) return playLink(app, v);
        if (byId) {
            standby = false;
            pauseOthers(AM);
            frontIfMusic(AM);
        }
        MediaController s;
        if (byId || (AM.equals(app) && "play".equals(c))) s = wake();
        else s = session(app);
        if (s == null && app == null && "play".equals(c)) s = wake();
        if (s == null) return new JSONObject().put("error", label(app) + " 未运行");
        MediaController.TransportControls t = s.getTransportControls();
        switch (String.valueOf(c)) {
            case "mediaid": t.playFromMediaId(v, new Bundle()); break;
            case "uri": t.playFromUri(Uri.parse(v), new Bundle()); break;
            case "play": t.play(); break;
            case "pause": t.pause(); break;
            case "next": t.skipToNext(); break;
            case "prev": t.skipToPrevious(); break;
            case "seek": t.seekTo(Long.parseLong(v)); break;
            case "skipq": t.skipToQueueItem(Long.parseLong(v)); break;
            default: return new JSONObject().put("error", "unknown command");
        }
        return new JSONObject().put("ok", true);
    }

    private byte[] art(String app) {
        MediaController c = session(app);
        MediaMetadata md = c == null ? null : c.getMetadata();
        Bitmap b = md == null ? null : md.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART);
        if (b == null && md != null) b = md.getBitmap(MediaMetadata.METADATA_KEY_ART);
        if (b == null) return null;
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        b.compress(Bitmap.CompressFormat.PNG, 100, o);
        return o.toByteArray();
    }
}
