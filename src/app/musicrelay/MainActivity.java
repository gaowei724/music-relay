package app.musicrelay;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.InputType;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.json.JSONArray;
import org.json.JSONObject;

public class MainActivity extends Activity {
    private static final int BG = 0xFF0E0E12;
    private static final int CARD = 0xFF1C1C23;
    private static final int CARD2 = 0x33FFFFFF;
    private static final int ACCENT = 0xFFFA2D48;
    private static final int DIM = 0xFF9A9AA5;
    private static final int GREEN = 0xFF30D158;
    private static final int RED = 0xFFFF453A;
    private static final int GREY = 0xFFB4B7BD;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newFixedThreadPool(4);
    private SharedPreferences prefs;
    // Set while a secondary page (permissions, encryption) is shown: it redraws that page
    // on resume, and Back returns to the main page.
    private Runnable sub;
    private LinearLayout content;

    private ImageView npArt;
    private ImageView npBg;
    private Runnable mirrorPaint;
    private TextView[] tabViews;
    private int tab; // selected tab, kept when the page is rebuilt (e.g. after a certificate import)
    private TextView npTitle;
    private TextView npArtist;
    private TextView npPos;
    private TextView npDur;
    private TextView npError;
    private TextView npPlay;
    private SeekBar npSeek;
    // Tablet volume: locked at max by default, since lowering it is digital attenuation.
    private SeekBar volSeek;
    private TextView volLabel;
    private TextView volLock;
    private TextView volMaxBtn;
    private boolean volLocked = true;
    private boolean volDragging;
    private int volMax = 15;
    private boolean dragging;
    private long pos;
    private long dur;
    private int playState;
    private long seenAt;
    private String artKey = "";
    private String queueKey = "";
    private LinearLayout queueBox;
    private boolean polling;
    private boolean looping;

    static String role(Context c) {
        return c.getSharedPreferences("am", MODE_PRIVATE).getString("role", "");
    }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        prefs = getSharedPreferences("am", MODE_PRIVATE);
        if (android.os.Build.VERSION.SDK_INT >= 33
                && checkSelfPermission("android.permission.POST_NOTIFICATIONS")
                        != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[] {"android.permission.POST_NOTIFICATIONS"}, 7);
        }
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);
        String r = prefs.getString("role", "");
        // Both roles show in recents: Samsung only offers "保持打开" (keep open) for tasks
        // listed there, and the user decides whether to lock or close Music Relay. Older
        // builds hid the player's task, so clear that flag on tasks they left behind.
        try {
            for (android.app.ActivityManager.AppTask t
                    : getSystemService(android.app.ActivityManager.class).getAppTasks()) {
                t.setExcludeFromRecents(false);
            }
        } catch (Exception ignored) {
        }
        if (r.equals("client")) RedirectService.restoreVolumeIfStale(this);
        if (r.equals("agent")) showAgent();
        else if (r.equals("client")) showClient();
        else showChooser();
    }

    @Override
    protected void onResume() {
        super.onResume();
        polling = true;
        if (sub != null) sub.run();
        else if (role(this).equals("client")) startPolling();
        else if (role(this).equals("agent")) showAgent();
    }

    // vivo ignores volume changes until the window has focus, so give back a volume left
    // muted by a killed mirror session here as well as in onCreate.
    @Override
    public void onWindowFocusChanged(boolean focus) {
        super.onWindowFocusChanged(focus);
        if (focus && role(this).equals("client")) RedirectService.restoreVolumeIfStale(this);
    }

    @Override
    protected void onPause() {
        super.onPause();
        polling = false;
        looping = false;
        ui.removeCallbacksAndMessages(null);
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private LinearLayout column() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        return l;
    }

    private TextView text(String s, int sp, int color) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        return t;
    }

    private Button button(String s, int color) {
        Button b = new Button(this);
        b.setText(s);
        b.setAllCaps(false);
        b.setTextColor(Color.WHITE);
        b.setTextSize(15);
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp(14));
        b.setBackground(d);
        return b;
    }

    private void page(View v) {
        v.setBackgroundColor(BG);
        v.setFitsSystemWindows(true);
        setContentView(v);
    }

    private GradientDrawable shape(int color, int radiusDp) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp(radiusDp));
        return d;
    }

    private TextView bold(String s, int sp, int color) {
        TextView t = text(s, sp, color);
        t.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        return t;
    }

    // Round glyph button (transport controls).
    private TextView roundButton(String glyph, int sizeDp, int bgColor, int textSp) {
        TextView b = text(glyph, textSp, Color.WHITE);
        b.setGravity(Gravity.CENTER);
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.OVAL);
        d.setColor(bgColor);
        b.setBackground(d);
        b.setClickable(true);
        b.setLayoutParams(new LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp)));
        return b;
    }

    private void ripple(View v) {
        android.util.TypedValue tv = new android.util.TypedValue();
        getTheme().resolveAttribute(android.R.attr.selectableItemBackground, tv, true);
        v.setForeground(getDrawable(tv.resourceId));
    }

    // Small colored dot + label, used for status lines.
    private TextView statusDot(String label, int color, int sp) {
        TextView t = text("", sp, Color.WHITE);
        setDot(t, label, color);
        return t;
    }

    private void setDot(TextView t, String label, int color) {
        android.text.SpannableString s = new android.text.SpannableString("●  " + label);
        s.setSpan(new android.text.style.ForegroundColorSpan(color), 0, 1, 0);
        t.setText(s);
    }

    // ---- first launch ----

    private void showChooser() {
        LinearLayout l = column();
        l.setGravity(Gravity.CENTER);
        l.setPadding(dp(32), dp(32), dp(32), dp(32));
        l.addView(bold("这台设备用来做什么？", 24, Color.WHITE));
        TextView why = text("两台设备装同一个 Music Relay，各选一个角色。以后可以在设置里切换。", 14, DIM);
        why.setPadding(0, dp(10), 0, dp(6));
        l.addView(why);
        Button a = button("播放器（接收端）\n接 DAC / 音箱、真正出声的设备", ACCENT);
        Button c = button("遥控端（客户端）\n手机：在自己的音乐 App 里点歌，播放器跟着播", CARD);
        a.setOnClickListener(v -> setRole("agent"));
        c.setOnClickListener(v -> setRole("client"));
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2);
        p.topMargin = dp(20);
        for (Button b : new Button[] {a, c}) {
            b.setPadding(dp(16), dp(16), dp(16), dp(16));
            l.addView(b, p);
        }
        page(l);
    }

    private void confirmRole(String r) {
        boolean agent = "agent".equals(r);
        new android.app.AlertDialog.Builder(this)
                .setTitle(agent ? "切换为播放器？" : "切换为遥控端？")
                .setMessage(agent ? "这台设备将接收手机的指令并出声，遥控端的镜像会停止。"
                        : "这台设备将不再作为播放器，手机将无法控制它。之后可以在遥控端的设置里切换回来。")
                .setPositiveButton("切换", (d, w) -> setRole(r))
                .setNegativeButton("取消", null)
                .show();
    }

    // Switching role stops whatever the old role was running on this device.
    private void setRole(String r) {
        String old = role(this);
        if ("client".equals(old) && !"client".equals(r) && RedirectService.isOn(this)) {
            startService(new Intent(this, RedirectService.class).setAction(RedirectService.ACTION_STOP));
        }
        if ("agent".equals(old) && !"agent".equals(r)) stopService(new Intent(this, AgentService.class));
        prefs.edit().putString("role", r).apply();
        sub = null;
        content = null;
        if ("agent".equals(r)) showAgent();
        else if ("client".equals(r)) showClient();
        else showChooser();
    }

    // ---- tablet ----

    private boolean hasAccess() {
        String s = Settings.Secure.getString(getContentResolver(), "enabled_notification_listeners");
        return s != null && s.contains(new ComponentName(this, Listener.class).flattenToString());
    }

    private String ip() {
        ConnectivityManager cm = getSystemService(ConnectivityManager.class);
        LinkProperties lp = cm.getLinkProperties(cm.getActiveNetwork());
        if (lp != null) {
            for (LinkAddress a : lp.getLinkAddresses()) {
                if (a.getAddress() instanceof java.net.Inet4Address) return a.getAddress().getHostAddress();
            }
        }
        return "未联网";
    }

    private void showAgent() {
        LinearLayout outer = column();
        outer.setGravity(Gravity.CENTER);
        outer.setPadding(dp(24), dp(24), dp(24), dp(24));
        LinearLayout l = column();
        l.setPadding(dp(28), dp(28), dp(28), dp(24));
        l.setBackground(shape(CARD, 28));
        outer.addView(l, new LinearLayout.LayoutParams(Math.min(
                getResources().getDisplayMetrics().widthPixels - dp(48), dp(560)), -2));
        TextView logo = bold("♫", 34, Color.WHITE);
        logo.setGravity(Gravity.CENTER);
        logo.setBackground(shape(ACCENT, 20));
        l.addView(logo, new LinearLayout.LayoutParams(dp(64), dp(64)));
        TextView title = bold("Music Relay 播放器", 26, Color.WHITE);
        title.setPadding(0, dp(18), 0, dp(4));
        l.addView(title);
        if (!hasAccess()) {
            l.addView(statusDot("需要开启通知访问，才能控制音乐 App", RED, 15));
            TextView g = bold("打开通知访问设置", 16, Color.WHITE);
            g.setGravity(Gravity.CENTER);
            g.setBackground(shape(ACCENT, 16));
            g.setOnClickListener(v -> startActivity(
                    new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)));
            LinearLayout.LayoutParams gp = new LinearLayout.LayoutParams(-1, dp(54));
            gp.topMargin = dp(24);
            l.addView(g, gp);
            page(outer);
            return;
        }
        boolean on = AgentService.enabled(this);
        if (on) startForegroundService(new Intent(this, AgentService.class));
        boolean err = on && !AgentService.lastError.isEmpty();
        l.addView(statusDot(!on ? "已关闭 · 手机无法控制播放器"
                : err ? "出错：" + AgentService.lastError : "运行中 · " + (Tls.serverSecure(this) ? "加密连接（HTTPS）" : "明文连接（HTTP）"),
                !on ? GREY : err ? RED : GREEN, 15));
        TextView power = bold(on ? "关闭播放器" : "开启播放器", 15, on ? Color.WHITE : BG);
        power.setGravity(Gravity.CENTER);
        power.setBackground(shape(on ? CARD2 : GREEN, 16));
        power.setOnClickListener(v -> {
            AgentService.setEnabled(this, !on);
            if (on) stopService(new Intent(this, AgentService.class));
            ui.postDelayed(this::showAgent, 300);
        });
        LinearLayout.LayoutParams pp = new LinearLayout.LayoutParams(-1, dp(50));
        pp.topMargin = dp(18);
        l.addView(power, pp);
        int missing = missingPermissions();
        if (missing > 0) l.addView(warning("⚠ 还有 " + missing + " 项权限没开，点此查看", v -> showPermissions(), 14));
        if (installed(AgentService.QQ_HD) && !installed(AgentService.QQ)) {
            // QQ音乐HD has no play-by-song-id, so the phone's songs cannot be followed in it.
            l.addView(warning("⚠ 本机只装了 QQ音乐HD。要镜像 QQ音乐，播放器上需要安装手机版 QQ音乐"
                    + "（和手机同一个 App），HD 版无法按歌曲播放", null, 10));
        }
        l.addView(infoBlock("地址", ip(), Color.WHITE));
        String code = AgentService.token(this);
        l.addView(infoBlock("配对码", code, ACCENT));
        TextView hint = text("在手机 Music Relay 里输入以上地址和配对码", 13, DIM);
        hint.setPadding(0, dp(14), 0, 0);
        l.addView(hint);
        LinearLayout row = new LinearLayout(this);
        TextView tile = text("＋ 下拉快捷开关", 15, Color.WHITE);
        tile.setGravity(Gravity.CENTER);
        tile.setBackground(shape(CARD2, 16));
        tile.setOnClickListener(v -> requestTile());
        TextView perm = text("🔑 权限", 15, Color.WHITE);
        perm.setGravity(Gravity.CENTER);
        perm.setBackground(shape(CARD2, 16));
        perm.setOnClickListener(v -> showPermissions());
        LinearLayout.LayoutParams t1 = new LinearLayout.LayoutParams(0, dp(50), 1);
        LinearLayout.LayoutParams t2 = new LinearLayout.LayoutParams(0, dp(50), 1);
        t2.leftMargin = dp(10);
        row.addView(tile, t1);
        row.addView(perm, t2);
        LinearLayout.LayoutParams tbp = new LinearLayout.LayoutParams(-1, -2);
        tbp.topMargin = dp(22);
        l.addView(row, tbp);
        LinearLayout links = new LinearLayout(this);
        links.setGravity(Gravity.CENTER);
        TextView enc = text("加密连接：" + (Tls.serverSecure(this) ? "开" : "关") + " ›", 14, DIM);
        enc.setPadding(dp(12), dp(16), dp(12), 0);
        enc.setOnClickListener(v -> showTransport());
        TextView sw = text("切换为遥控端 ›", 14, DIM);
        sw.setPadding(dp(12), dp(16), dp(12), 0);
        sw.setOnClickListener(v -> confirmRole("client"));
        links.addView(enc);
        links.addView(sw);
        l.addView(links);
        ScrollView sc = new ScrollView(this);
        sc.setFillViewport(true);
        sc.addView(outer, new ViewGroup.LayoutParams(-1, -2));
        page(sc);
    }

    private boolean hasUsageAccess() {
        android.app.AppOpsManager ops = getSystemService(android.app.AppOpsManager.class);
        return ops.unsafeCheckOpNoThrow(android.app.AppOpsManager.OPSTR_GET_USAGE_STATS,
                android.os.Process.myUid(), getPackageName()) == android.app.AppOpsManager.MODE_ALLOWED;
    }

    private View infoBlock(String label, String value, int color) {
        LinearLayout b = column();
        b.setPadding(dp(18), dp(14), dp(18), dp(14));
        b.setBackground(shape(0xFF26262F, 18));
        b.addView(text(label, 13, DIM));
        TextView v = bold(value, 30, color);
        v.setTypeface(android.graphics.Typeface.create(android.graphics.Typeface.MONOSPACE,
                android.graphics.Typeface.BOLD));
        v.setTextIsSelectable(true);
        b.addView(v);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2);
        p.topMargin = dp(16);
        b.setLayoutParams(p);
        return b;
    }

    // ---- remote ----

    private void showClient() {
        if (prefs.getString("host", "").isEmpty() || prefs.getString("code", "").isEmpty()) {
            showPair();
            return;
        }
        LinearLayout root = column();
        content = column();
        root.addView(content, new LinearLayout.LayoutParams(-1, 0, 1));
        LinearLayout tabs = new LinearLayout(this);
        tabs.setPadding(dp(12), dp(4), dp(12), dp(4));
        String[] names = {"♪\n正在播放", "☰\n队列", "⚙\n设置"};
        tabViews = new TextView[names.length];
        for (int i = 0; i < names.length; i++) {
            final int idx = i;
            TextView t = text(names[i], 13, DIM);
            t.setGravity(Gravity.CENTER);
            t.setPadding(0, dp(5), 0, dp(5));
            t.setOnClickListener(v -> showTab(idx));
            tabViews[i] = t;
            tabs.addView(t, new LinearLayout.LayoutParams(0, -2, 1));
        }
        tabs.setBackground(shape(CARD, 0));
        root.addView(tabs);
        page(root);
        showTab(tab);
        startPolling();
    }

    private void showPair() {
        LinearLayout l = column();
        l.setPadding(dp(28), dp(48), dp(28), dp(28));
        l.addView(text("连接播放器", 26, Color.WHITE));
        l.addView(text("\n地址和配对码显示在播放器的 Music Relay 里。\n", 15, DIM));
        EditText host = field("地址，例如 192.168.1.20", InputType.TYPE_CLASS_TEXT);
        EditText code = field("4 位配对码", InputType.TYPE_CLASS_NUMBER);
        host.setText(prefs.getString("host", ""));
        l.addView(host);
        l.addView(code);
        Button ok = button("连接", ACCENT);
        ok.setOnClickListener(v -> {
            prefs.edit().putString("host", host.getText().toString().trim())
                    .putString("code", code.getText().toString().trim()).apply();
            bg(() -> {
                String err = pairError();
                ui.post(() -> {
                    if (err != null) {
                        new android.app.AlertDialog.Builder(this).setTitle("连接失败")
                                .setMessage(err).setPositiveButton("好", null).show();
                        prefs.edit().remove("code").apply();
                    } else {
                        showClient();
                    }
                });
            });
        });
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, dp(56));
        p.topMargin = dp(20);
        l.addView(ok, p);
        TextView sw = text("这台设备是播放器？切换为播放器 ›", 14, DIM);
        sw.setGravity(Gravity.CENTER);
        sw.setPadding(0, dp(24), 0, dp(8));
        sw.setOnClickListener(v -> confirmRole("agent"));
        l.addView(sw);
        ScrollView sc = new ScrollView(this);
        sc.addView(l);
        page(sc);
    }

    // Why pairing failed, in words the user can act on; null when it worked.
    private String pairError() {
        try {
            int rc = Tls.pair(this);
            if (rc == 200) return null;
            if (rc == 401) return "配对码不对，请对照播放器上显示的 4 位配对码。";
            if (rc == 429) return "配对码输错次数太多，请 1 分钟后再试。";
            return "播放器返回错误 " + rc + "。";
        } catch (java.net.SocketTimeoutException e) {
            return "连不上 " + prefs.getString("host", "") + "：网络不通或超时。请确认两台设备在同一个局域网，"
                    + "路由器没有开启“AP 隔离 / 客户端隔离”，播放器上的 Music Relay 正在运行。";
        } catch (java.net.ConnectException e) {
            return "播放器拒绝连接：播放器上的 Music Relay 可能已关闭，或地址填错了。";
        } catch (java.io.IOException e) {
            return "连接失败：" + e.getMessage();
        } catch (Exception e) {
            return "连接失败：" + e;
        }
    }

    private EditText field(String hint, int type) {
        EditText e = new EditText(this);
        e.setHint(hint);
        e.setHintTextColor(DIM);
        e.setTextColor(Color.WHITE);
        e.setTextSize(16);
        e.setInputType(type);
        e.setSingleLine(true);
        e.setPadding(dp(18), dp(14), dp(18), dp(14));
        e.setBackground(shape(CARD, 16));
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2);
        p.topMargin = dp(12);
        e.setLayoutParams(p);
        return e;
    }

    private void showTab(int i) {
        tab = i;
        content.removeAllViews();
        queueBox = null;
        npArt = null;
        npBg = null;
        mirrorPaint = null;
        volSeek = null;
        volLocked = true;
        if (tabViews != null) {
            for (int k = 0; k < tabViews.length; k++) {
                tabViews[k].setTextColor(k == i ? Color.WHITE : DIM);
                tabViews[k].setBackground(k == i ? shape(CARD2, 14) : null);
            }
        }
        if (i == 0) buildNowPlaying();
        else if (i == 1) buildQueue();
        else buildSettings();
    }

    private void buildNowPlaying() {
        android.widget.FrameLayout frame = new android.widget.FrameLayout(this);
        npBg = new ImageView(this);
        npBg.setScaleType(ImageView.ScaleType.CENTER_CROP);
        npBg.setAlpha(0.45f);
        if (android.os.Build.VERSION.SDK_INT >= 31) {
            npBg.setRenderEffect(android.graphics.RenderEffect.createBlurEffect(
                    dp(60), dp(60), android.graphics.Shader.TileMode.CLAMP));
        }
        frame.addView(npBg, new android.widget.FrameLayout.LayoutParams(-1, -1));
        View scrim = new View(this);
        scrim.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[] {0x55000000, 0xCC0E0E12, BG}));
        frame.addView(scrim, new android.widget.FrameLayout.LayoutParams(-1, -1));

        LinearLayout l = column();
        l.setGravity(Gravity.CENTER_HORIZONTAL);
        l.setPadding(dp(24), dp(14), dp(24), dp(12));
        npArt = new ImageView(this);
        npArt.setScaleType(ImageView.ScaleType.CENTER_CROP);
        npArt.setClipToOutline(true);
        npArt.setBackground(shape(CARD, 22));
        npArt.setElevation(dp(16));
        // Sized so that art, controls, source, mirror switch and volume fit one phone screen.
        android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
        int w = Math.min(Math.min(dm.widthPixels - dp(64), dp(420)), (int) (dm.heightPixels * 0.30f));
        LinearLayout.LayoutParams ap = new LinearLayout.LayoutParams(w, w);
        l.addView(npArt, ap);
        npTitle = bold("", 21, Color.WHITE);
        npTitle.setPadding(0, dp(12), 0, 0);
        npTitle.setSingleLine(true);
        npTitle.setEllipsize(TextUtils.TruncateAt.END);
        npArtist = text("", 15, 0xFFC8C8D0);
        npArtist.setSingleLine(true);
        npArtist.setEllipsize(TextUtils.TruncateAt.END);
        npError = text("", 14, RED);
        l.addView(npTitle, new LinearLayout.LayoutParams(-1, -2));
        l.addView(npArtist, new LinearLayout.LayoutParams(-1, -2));
        l.addView(npError, new LinearLayout.LayoutParams(-1, -2));
        npSeek = new SeekBar(this);
        npSeek.setProgressTintList(android.content.res.ColorStateList.valueOf(Color.WHITE));
        npSeek.setThumbTintList(android.content.res.ColorStateList.valueOf(Color.WHITE));
        npSeek.setProgressBackgroundTintList(android.content.res.ColorStateList.valueOf(0x55FFFFFF));
        npSeek.setMax(1000);
        npSeek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean user) {
                if (user) npPos.setText(time(dur * p / 1000));
            }
            @Override public void onStartTrackingTouch(SeekBar s) {
                dragging = true;
            }
            @Override public void onStopTrackingTouch(SeekBar s) {
                dragging = false;
                pos = dur * s.getProgress() / 1000;
                seenAt = System.currentTimeMillis();
                cmd("seek", String.valueOf(pos));
            }
        });
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(-1, -2);
        sp.topMargin = dp(6);
        l.addView(npSeek, sp);
        LinearLayout times = new LinearLayout(this);
        npPos = text("0:00", 13, DIM);
        npDur = text("0:00", 13, DIM);
        npDur.setGravity(Gravity.END);
        times.addView(npPos, new LinearLayout.LayoutParams(0, -2, 1));
        times.addView(npDur, new LinearLayout.LayoutParams(0, -2, 1));
        l.addView(times, new LinearLayout.LayoutParams(-1, -2));
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER);
        TextView prev = roundButton("⏮", 50, CARD2, 19);
        npPlay = roundButton("▶", 64, Color.WHITE, 26);
        npPlay.setTextColor(BG);
        TextView next = roundButton("⏭", 50, CARD2, 19);
        prev.setOnClickListener(v -> cmd("prev", ""));
        next.setOnClickListener(v -> cmd("next", ""));
        npPlay.setOnClickListener(v -> cmd(playState == 3 ? "pause" : "play", ""));
        for (TextView b : new TextView[] {prev, npPlay, next}) {
            LinearLayout.LayoutParams bp = (LinearLayout.LayoutParams) b.getLayoutParams();
            bp.setMargins(dp(14), 0, dp(14), 0);
            row.addView(b, bp);
        }
        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(-1, -2);
        rp.topMargin = dp(4);
        l.addView(row, rp);
        // Mirror status card: grey = off, green = mirroring, red = sync error.
        LinearLayout card = new LinearLayout(this);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setPadding(dp(16), dp(10), dp(12), dp(10));
        LinearLayout labels = column();
        TextView st = bold("", 16, Color.WHITE);
        TextView sub = text("", 13, DIM);
        labels.addView(st);
        labels.addView(sub);
        card.addView(labels, new LinearLayout.LayoutParams(0, -2, 1));
        TextView sw = bold("", 14, Color.WHITE);
        sw.setGravity(Gravity.CENTER);
        sw.setPadding(dp(16), dp(8), dp(16), dp(8));
        card.addView(sw);
        mirrorPaint = () -> {
            boolean on = RedirectService.isOn(this);
            boolean err = on && RedirectService.healthOf(this) == RedirectService.ERROR;
            int c = !on ? GREY : err ? RED : GREEN;
            setDot(st, !on ? "镜像已关闭" : err ? "同步错误" : "镜像中", c);
            String srcApp = RedirectService.source(this);
            String srcName = RedirectService.AUTO.equals(srcApp) ? "正在播放的音乐 App"
                    : AgentService.label(srcApp);
            sub.setText(!on ? "开启后在本机" + srcName + "操作，播放器跟随"
                    : err ? "播放器连不上，检查网络或播放器" : "本机已静音，由播放器出声");
            card.setBackground(shape(!on ? CARD : err ? 0xFF3A1A1C : 0xFF16301F, 20));
            sw.setText(on ? "关闭" : "开启");
            sw.setTextColor(on ? Color.WHITE : BG);
            sw.setBackground(shape(on ? CARD2 : GREEN, 14));
        };
        View.OnClickListener toggle = v -> {
            if (!RedirectService.isOn(this) && !hasAccess()) {
                toast("先开启通知访问，才能读取本机音乐 App");
                startActivity(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS));
                return;
            }
            boolean wasOn = RedirectService.isOn(this);
            if (!wasOn && enabledApps(this).isEmpty()) {
                toast("没有选择任何音乐 App，镜像无法工作");
                return;
            }
            if (!wasOn) RedirectService.muteNow(this);
            startForegroundService(new Intent(this, RedirectService.class)
                    .setAction(RedirectService.ACTION_TOGGLE));
            if (wasOn) RedirectService.unmuteNow(this);
            ui.postDelayed(() -> {
                if (mirrorPaint != null) mirrorPaint.run();
            }, 600);
        };
        card.setOnClickListener(toggle);
        sw.setOnClickListener(toggle);
        mirrorPaint.run();
        LinearLayout.LayoutParams sp2 = new LinearLayout.LayoutParams(-1, -2);
        sp2.topMargin = dp(14);
        l.addView(buildSource(), sp2);
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(-1, -2);
        tp.topMargin = dp(8);
        l.addView(card, tp);
        if (!RedirectService.isOn(this) && RedirectService.leftMuted(this)) {
            // vivo ignores volume changes made without a fresh tap, so restoring it on its own
            // after an interrupted mirror session can fail; let the user do it with one tap.
            l.addView(warning("🔇 手机仍是静音（上次镜像没有正常结束）· 点此恢复音量", v -> {
                RedirectService.unmuteNow(this);
                showTab(0);
            }, 8));
        }
        l.addView(buildVolume());
        ScrollView sc = new ScrollView(this);
        sc.addView(l);
        frame.addView(sc, new android.widget.FrameLayout.LayoutParams(-1, -1));
        content.addView(frame, new LinearLayout.LayoutParams(-1, -1));
        artKey = "";
    }

    private View buildVolume() {
        LinearLayout box = column();
        box.setPadding(dp(16), dp(8), dp(16), dp(6));
        box.setBackground(shape(CARD, 20));
        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        volLabel = bold("播放器音量", 15, Color.WHITE);
        top.addView(volLabel, new LinearLayout.LayoutParams(0, -2, 1));
        volMaxBtn = bold("恢复最大", 13, BG);
        volMaxBtn.setPadding(dp(12), dp(6), dp(12), dp(6));
        volMaxBtn.setBackground(shape(GREEN, 12));
        volMaxBtn.setOnClickListener(v -> {
            volLocked = true;
            cmd("volmax", "");
        });
        top.addView(volMaxBtn);
        volLock = bold("🔒 解锁", 13, Color.WHITE);
        volLock.setPadding(dp(12), dp(6), dp(12), dp(6));
        volLock.setBackground(shape(CARD2, 12));
        volLock.setOnClickListener(v -> {
            volLocked = !volLocked;
            paintVolume(volSeek.getProgress());
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.leftMargin = dp(8);
        top.addView(volLock, lp);
        box.addView(top);
        volSeek = new SeekBar(this);
        volSeek.setMax(volMax);
        volSeek.setProgressTintList(android.content.res.ColorStateList.valueOf(Color.WHITE));
        volSeek.setThumbTintList(android.content.res.ColorStateList.valueOf(Color.WHITE));
        volSeek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean user) {
                if (user) paintVolume(p);
            }
            @Override public void onStartTrackingTouch(SeekBar s) {
                volDragging = true;
            }
            @Override public void onStopTrackingTouch(SeekBar s) {
                volDragging = false;
                cmd("vol", String.valueOf(s.getProgress()));
            }
        });
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(-1, -2);
        sp.topMargin = dp(2);
        box.addView(volSeek, sp);
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(-1, -2);
        bp.topMargin = dp(8);
        box.setLayoutParams(bp);
        paintVolume(volMax);
        return box;
    }

    // Mirror source: which app on this phone is mirrored. The player plays in the same app.
    // Music apps the user wants to mirror (chosen under "管理 App"); Apple Music until changed.
    static List<String> enabledApps(android.content.Context c) {
        String s = c.getSharedPreferences("am", MODE_PRIVATE).getString("apps", AgentService.AM);
        List<String> out = new ArrayList<>();
        for (String a : AgentService.APPS) if (("," + s + ",").contains("," + a + ",")) out.add(a);
        return out;
    }

    private boolean installed(String pkg) {
        try {
            getPackageManager().getPackageInfo(pkg, 0);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    // Keeps the mirror source on an app that is still enabled.
    private void fixSource() {
        List<String> apps = enabledApps(this);
        String cur = RedirectService.source(this);
        boolean ok = RedirectService.AUTO.equals(cur) ? apps.size() > 1 : apps.contains(cur);
        if (!ok && !apps.isEmpty()) RedirectService.setSource(this, apps.get(0));
    }

    private TextView chip(String label, boolean sel) {
        TextView c = bold(label, 13, sel ? BG : Color.WHITE);
        c.setGravity(Gravity.CENTER);
        c.setSingleLine(true);
        c.setPadding(dp(4), dp(8), dp(4), dp(8));
        // Shrink "Apple Music" to fit a quarter of a phone screen instead of cutting it.
        c.setAutoSizeTextTypeUniformWithConfiguration(9, 13, 1,
                android.util.TypedValue.COMPLEX_UNIT_SP);
        c.setBackground(shape(sel ? Color.WHITE : CARD, 14));
        return c;
    }

    // Mirror source picker: one chip per app enabled in Settings, plus 自动 when there are several.
    private View buildSource() {
        LinearLayout box = column();
        Runnable[] render = new Runnable[1];
        render[0] = () -> {
            box.removeAllViews();
            fixSource();
            List<String> apps = enabledApps(this);
            String cur = RedirectService.source(this);
            box.addView(text("镜像来源", 13, DIM));
            LinearLayout row = new LinearLayout(this);
            List<String> opts = new ArrayList<>(apps);
            if (apps.size() > 1) opts.add(RedirectService.AUTO);
            for (int i = 0; i < opts.size(); i++) {
                String pkg = opts.get(i);
                TextView c = chip(RedirectService.AUTO.equals(pkg) ? "自动"
                        : AgentService.NETEASE.equals(pkg) ? "网易云"
                        : AgentService.CLASSICAL.equals(pkg) ? "古典乐" : AgentService.label(pkg),
                        pkg.equals(cur));
                c.setOnClickListener(v -> {
                    RedirectService.setSource(this, pkg);
                    artKey = "";
                    render[0].run();
                    if (mirrorPaint != null) mirrorPaint.run();
                });
                LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(0, -2, 1);
                if (i > 0) cp.leftMargin = dp(8);
                row.addView(c, cp);
            }
            if (apps.isEmpty()) {
                box.addView(statusDot("没有选择任何音乐 App，镜像无法工作。请到“设置”里选择", RED, 13));
            } else {
                LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(-1, -2);
                rp.topMargin = dp(6);
                box.addView(row, rp);
            }
        };
        render[0].run();
        return box;
    }

    // Which apps to offer as mirror sources. Apps not installed on this phone cannot be picked.
    private View buildAppManager(Runnable onChange) {
        List<String> apps = enabledApps(this);
        LinearLayout panel = column();
        panel.setPadding(dp(16), dp(4), dp(16), dp(4));
        panel.setBackground(shape(CARD, 16));
        for (String a : AgentService.APPS) {
            boolean has = installed(a);
            boolean on = apps.contains(a);
            LinearLayout line = new LinearLayout(this);
            line.setGravity(Gravity.CENTER_VERTICAL);
            line.setPadding(0, dp(12), 0, dp(12));
            line.addView(bold(AgentService.label(a), 15, has ? Color.WHITE : DIM),
                    new LinearLayout.LayoutParams(0, -2, 1));
            line.addView(text(!has ? "未安装" : on ? "✓ 显示" : "不显示", 14,
                    !has ? DIM : on ? GREEN : DIM));
            if (has) {
                line.setOnClickListener(v -> {
                    List<String> next = new ArrayList<>(enabledApps(this));
                    if (on) next.remove(a);
                    else next.add(a);
                    prefs.edit().putString("apps", android.text.TextUtils.join(",", next)).apply();
                    fixSource();
                    onChange.run();
                });
            }
            panel.addView(line);
        }
        if (apps.isEmpty()) {
            panel.addView(statusDot("一个都不选，镜像无法工作", RED, 13));
        }
        return panel;
    }

    // SRC check on the player: what the app sends vs what the USB DAC gets, and why.
    private View buildSrc() {
        LinearLayout box = column();
        box.setPadding(dp(18), dp(12), dp(18), dp(12));
        box.setBackground(shape(CARD, 20));
        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = bold("SRC 检测", 15, Color.WHITE);
        top.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        TextView run = bold("检测", 13, BG);
        run.setPadding(dp(12), dp(6), dp(12), dp(6));
        run.setBackground(shape(Color.WHITE, 12));
        top.addView(run);
        box.addView(top);
        // One summary line; the details fold out on tap so the page stays short.
        TextView summary = text("检测播放器输出到 USB DAC 时有没有被重采样", 13, DIM);
        summary.setPadding(0, dp(6), 0, 0);
        box.addView(summary);
        TextView body = text("", 13, Color.WHITE);
        body.setPadding(0, dp(8), 0, 0);
        body.setVisibility(View.GONE);
        box.addView(body);
        box.setOnClickListener(v -> {
            if (body.getText().length() == 0) return;
            boolean open = body.getVisibility() != View.VISIBLE;
            body.setVisibility(open ? View.VISIBLE : View.GONE);
            summary.setText(summary.getText().toString().replaceAll(" [▾▴]$", "") + (open ? " ▴" : " ▾"));
        });
        run.setOnClickListener(v -> {
            summary.setText("检测中…");
            body.setText("");
            body.setVisibility(View.GONE);
            bg(() -> {
                JSONObject r = api("/src");
                ui.post(() -> {
                    if (r == null) {
                        summary.setText("连接播放器失败");
                        return;
                    }
                    if (r.has("error")) {
                        summary.setText("播放器版本太旧，请更新播放器上的 Music Relay");
                        return;
                    }
                    boolean clean = r.optJSONObject("track") != null
                            && r.optJSONObject("track").optBoolean("bitPerfect");
                    title.setText("SRC 检测");
                    summary.setText(r.optString("verdict")
                            + (r.has("track") ? (r.optBoolean("canBypass") ? " · 可绕过" : " · 不能绕过") : "")
                            + " ▾");
                    summary.setTextColor(clean ? GREEN : r.has("track") ? 0xFFFFB340 : DIM);
                    StringBuilder b = new StringBuilder();
                    JSONObject t = r.optJSONObject("track");
                    if (r.has("device")) b.append("DAC：").append(r.optString("device")).append('\n');
                    if (t != null) {
                        b.append("来源：").append(t.optString("app")).append(" ")
                                .append(t.optInt("rate")).append(" Hz\n")
                                .append("输出：").append(r.optInt("outRate")).append(" Hz · ")
                                .append(r.optString("outFormat")).append('\n');
                    }
                    JSONArray why = r.optJSONArray("why");
                    for (int i = 0; why != null && i < why.length(); i++) {
                        b.append('\n').append("• ").append(why.optString(i));
                    }
                    body.setText(b.toString().trim());
                });
            });
        });
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(-1, -2);
        bp.topMargin = dp(14);
        box.setLayoutParams(bp);
        return box;
    }

    // Shows the level and warns when it is below max (digital attenuation before the DAC).
    private void paintVolume(int vol) {
        if (volSeek == null) return;
        boolean full = vol >= volMax;
        volSeek.setEnabled(!volLocked);
        volSeek.setAlpha(volLocked ? 0.45f : 1f);
        volLock.setText(volLocked ? "🔒 解锁" : "🔓 锁定");
        volMaxBtn.setVisibility(full ? View.GONE : View.VISIBLE);
        if (full) {
            volLabel.setText("播放器音量 · 最大（无损）");
            volLabel.setTextColor(Color.WHITE);
        } else {
            volLabel.setText("播放器音量 " + vol + "/" + volMax + " · 数字衰减中");
            volLabel.setTextColor(0xFFFFB340);
        }
    }

    private TextView section(String s) {
        TextView t = text(s, 19, Color.WHITE);
        t.setPadding(dp(4), dp(18), dp(4), dp(8));
        return t;
    }

    private View row(String title, String sub, View.OnClickListener click) {
        LinearLayout r = new LinearLayout(this);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(dp(8), dp(8), dp(8), dp(8));
        r.setOnClickListener(click);
        ripple(r);
        LinearLayout c = column();
        TextView a = text(title, 16, Color.WHITE);
        a.setSingleLine(true);
        a.setEllipsize(TextUtils.TruncateAt.END);
        TextView b = text(sub, 13, DIM);
        b.setSingleLine(true);
        b.setEllipsize(TextUtils.TruncateAt.END);
        c.addView(a);
        c.addView(b);
        r.addView(c, new LinearLayout.LayoutParams(0, -2, 1));
        return r;
    }

    private void buildQueue() {
        queueBox = column();
        queueBox.setPadding(dp(14), dp(8), dp(14), dp(8));
        queueBox.addView(section("播放队列"));
        ScrollView sc = new ScrollView(this);
        sc.addView(queueBox);
        content.addView(sc, new LinearLayout.LayoutParams(-1, -1));
        queueKey = "";
    }

    // Settings tab: which apps to mirror, the SRC check, the quick tile and pairing.
    private void buildSettings() {
        LinearLayout l = column();
        l.setPadding(dp(18), dp(8), dp(18), dp(24));
        l.addView(section("镜像的音乐 App"));
        l.addView(text("勾选手机上要镜像的 App，主页的“镜像来源”只显示这些。播放器上要装同一个 App："
                + "QQ音乐请装手机版，不支持 QQ音乐HD", 13, DIM));
        LinearLayout apps = column();
        Runnable[] paint = new Runnable[1];
        paint[0] = () -> {
            apps.removeAllViews();
            apps.addView(buildAppManager(paint[0]));
        };
        paint[0].run();
        LinearLayout.LayoutParams ap = new LinearLayout.LayoutParams(-1, -2);
        ap.topMargin = dp(10);
        l.addView(apps, ap);
        l.addView(section("音质"));
        l.addView(buildSrc());
        l.addView(section("快捷开关"));
        TextView tile = bold("＋ 添加下拉快捷开关", 15, Color.WHITE);
        tile.setGravity(Gravity.CENTER);
        tile.setPadding(0, dp(14), 0, dp(14));
        tile.setBackground(shape(CARD, 16));
        tile.setOnClickListener(v -> requestTile());
        l.addView(tile);
        l.addView(section("权限"));
        int missing = missingPermissions();
        TextView perm = bold(missing == 0 ? "🔑 权限（已全部开启）" : "🔑 权限（" + missing + " 项未开启）",
                15, missing == 0 ? Color.WHITE : 0xFFFFB340);
        perm.setGravity(Gravity.CENTER);
        perm.setPadding(0, dp(14), 0, dp(14));
        perm.setBackground(shape(CARD, 16));
        perm.setOnClickListener(v -> showPermissions());
        l.addView(perm);
        l.addView(section("播放器"));
        TextView pair = bold("重新配对（当前 " + prefs.getString("host", "") + "）", 15, Color.WHITE);
        pair.setGravity(Gravity.CENTER);
        pair.setPadding(0, dp(14), 0, dp(14));
        pair.setBackground(shape(CARD, 16));
        pair.setOnClickListener(v -> showPair());
        l.addView(pair);
        TextView enc = text(Tls.clientSecure(this) ? "🔒 已加密连接（配对时自动识别）"
                : "未加密连接。播放器上打开“加密连接”后，在这里重新配对即可", 13, DIM);
        enc.setPadding(dp(4), dp(10), dp(4), 0);
        l.addView(enc);
        TextView sw = bold("切换为播放器", 15, Color.WHITE);
        sw.setGravity(Gravity.CENTER);
        sw.setPadding(0, dp(14), 0, dp(14));
        sw.setBackground(shape(CARD, 16));
        sw.setOnClickListener(v -> confirmRole("agent"));
        l.addView(sw);
        TextView about = text("Music Relay " + version() + " · 只转发播放控制，不传输音频，不涉及 DRM",
                12, DIM);
        about.setPadding(0, dp(24), 0, 0);
        l.addView(about);
        ScrollView sc = new ScrollView(this);
        sc.addView(l);
        content.addView(sc, new LinearLayout.LayoutParams(-1, -1));
    }

    // While mirroring, the phone's own music app leads and the mirror carries every change to
    // the player. So the controls here act on that app; otherwise the phone and the player
    // would drift apart. Null when not mirroring or the app has no session.
    private android.media.session.MediaController localSession(String app) {
        if (!RedirectService.isOn(this)) return null;
        String src = app != null ? app : RedirectService.source(this);
        android.media.session.MediaController first = null;
        try {
            for (android.media.session.MediaController c
                    : getSystemService(android.media.session.MediaSessionManager.class)
                            .getActiveSessions(new ComponentName(this, Listener.class))) {
                String p = c.getPackageName();
                if (RedirectService.AUTO.equals(src) ? !AgentService.supported(p) : !src.equals(p)) continue;
                android.media.session.PlaybackState st = c.getPlaybackState();
                if (st != null && st.getState() == android.media.session.PlaybackState.STATE_PLAYING) return c;
                if (first == null) first = c;
            }
        } catch (SecurityException ignored) {
        }
        return first;
    }

    // Runs a control on the phone's app while mirroring; false when it does not apply.
    private boolean localCmd(String c, String v) {
        if (!RedirectService.isOn(this)) return false;
        if (!"play".equals(c) && !"pause".equals(c) && !"next".equals(c)
                && !"prev".equals(c) && !"seek".equals(c) && !"skipq".equals(c)) return false;
        android.media.session.MediaController s = localSession(null);
        if (s == null) {
            String app = RedirectService.source(this);
            toast("请先在手机上打开" + (RedirectService.AUTO.equals(app) ? "音乐 App" : AgentService.label(app)));
            return true;
        }
        android.media.session.MediaController.TransportControls t = s.getTransportControls();
        switch (c) {
            case "play": t.play(); break;
            case "pause": t.pause(); break;
            case "next": t.skipToNext(); break;
            case "prev": t.skipToPrevious(); break;
            case "seek": t.seekTo(Long.parseLong(v)); break;
            default: t.skipToQueueItem(Long.parseLong(v)); break;
        }
        return true;
    }

    // The phone app's queue, shown instead of the player's while mirroring so that picking
    // an item plays it on the phone (and the mirror follows).
    private JSONObject localQueue() {
        android.media.session.MediaController s = localSession(null);
        JSONObject o = new JSONObject();
        try {
            JSONArray q = new JSONArray();
            List<android.media.session.MediaSession.QueueItem> items = s == null ? null : s.getQueue();
            for (int i = 0; items != null && i < items.size(); i++) {
                android.media.session.MediaSession.QueueItem it = items.get(i);
                q.put(new JSONObject().put("id", it.getQueueId())
                        .put("title", String.valueOf(it.getDescription().getTitle()))
                        .put("artist", String.valueOf(it.getDescription().getSubtitle())));
            }
            android.media.session.PlaybackState st = s == null ? null : s.getPlaybackState();
            o.put("queue", q).put("activeId", st == null ? -1 : st.getActiveQueueItemId());
        } catch (Exception ignored) {
        }
        return o;
    }

    private void cmd(String c, String v) {
        if (localCmd(c, v)) return;
        bg(() -> {
            String app = appArg();
            JSONObject r = api("/cmd?c=" + c + "&v=" + enc(v) + (app == null ? "" : "&app=" + app));
            String err = r == null ? "连接失败" : r.optString("error");
            ui.post(() -> {
                if (!err.isEmpty()) toast(err);
            });
        });
    }

    // ---- state ----

    private void startPolling() {
        if (polling && !looping) {
            looping = true;
            poll();
        }
    }

    private void poll() {
        if (!polling || content == null) {
            looping = false;
            return;
        }
        bg(() -> {
            JSONObject s = api(withApp("/state"));
            ui.post(() -> apply(s));
        });
        ui.postDelayed(this::poll, 1000);
    }

    private void apply(JSONObject s) {
        if (npError != null) npError.setText(s == null ? "连接不上播放器" : "");
        if (s == null) return;
        playState = s.optInt("state");
        pos = s.optLong("pos");
        dur = s.optLong("duration");
        seenAt = System.currentTimeMillis();
        if (volSeek != null && s.has("vol") && !volDragging) {
            volMax = s.optInt("volMax", volMax);
            volSeek.setMax(volMax);
            volSeek.setProgress(s.optInt("vol"));
            paintVolume(s.optInt("vol"));
        }
        if (npArt != null) {
            npTitle.setText(s.optBoolean("alive") ? s.optString("title")
                    : "播放器上的" + AgentService.label(s.optString("app")) + "未运行");
            npArtist.setText(s.optString("artist"));
            npPlay.setText(playState == 3 ? "❚❚" : "▶");
            if (mirrorPaint != null) mirrorPaint.run();
            npDur.setText(time(dur));
            if (!dragging) {
                npPos.setText(time(pos));
                npSeek.setProgress(dur > 0 ? (int) (pos * 1000 / dur) : 0);
            }
            String key = s.optString("title") + "|" + s.optString("album");
            if (!key.equals(artKey)) {
                artKey = key;
                loadNowPlayingArt();
            }
        }
        if (queueBox != null) renderQueue(RedirectService.isOn(this) ? localQueue() : s);
    }

    private void loadNowPlayingArt() {
        final ImageView target = npArt;
        final ImageView back = npBg;
        bg(() -> {
            byte[] b = raw(withApp("/art"));
            Bitmap bm = b == null ? null : BitmapFactory.decodeByteArray(b, 0, b.length);
            ui.post(() -> {
                if (target == npArt) target.setImageBitmap(bm);
                if (back != null && back == npBg) back.setImageBitmap(bm);
            });
        });
    }

    private void renderQueue(JSONObject s) {
        JSONArray q = s.optJSONArray("queue");
        long active = s.optLong("activeId", -1);
        String key = active + ":" + q;
        if (key.equals(queueKey)) return;
        queueKey = key;
        queueBox.removeAllViews();
        queueBox.addView(section("播放队列"));
        if (q == null || q.length() == 0) {
            queueBox.addView(text("这个 App 没有提供播放队列", 14, DIM));
        }
        for (int i = 0; q != null && i < q.length(); i++) {
            JSONObject it = q.optJSONObject(i);
            long id = it.optLong("id");
            String title = (id == active ? "▶ " : "") + it.optString("title");
            queueBox.addView(row(title, it.optString("artist"), v -> cmd("skipq", String.valueOf(id))));
        }
    }

    private static String time(long ms) {
        long s = Math.max(0, ms) / 1000;
        return String.format(Locale.US, "%d:%02d", s / 60, s % 60);
    }

    // ---- network ----

    private void bg(Runnable r) {
        io.execute(r);
    }

    private static String enc(String s) {
        try {
            return URLEncoder.encode(s, "UTF-8");
        } catch (Exception e) {
            return "";
        }
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }

    private void requestTile() {
        try {
            if (android.os.Build.VERSION.SDK_INT >= 33) {
                android.app.StatusBarManager sbm = getSystemService(android.app.StatusBarManager.class);
                sbm.requestAddTileService(
                        new ComponentName(this, QsTile.class), "Music Relay",
                        android.graphics.drawable.Icon.createWithResource(this,
                                R.drawable.ic_stat),
                        getMainExecutor(), r -> {
                        });
            } else {
                toast("下拉通知面板 → 编辑按钮 → 找到 Music Relay 拖上去");
            }
        } catch (Throwable t) {
            toast("请在下拉面板的编辑里手动添加 Music Relay 开关");
        }
    }

    // The mirror source picked on this phone, so the player shows and controls the same app.
    // Null in auto mode: the player then uses whichever app is playing.
    private String appArg() {
        String s = RedirectService.source(this);
        return RedirectService.AUTO.equals(s) ? null : AgentService.playerApp(s);
    }

    private String withApp(String path) {
        String app = appArg();
        return app == null ? path : path + (path.contains("?") ? "&" : "?") + "app=" + app;
    }

    private JSONObject api(String path) {
        byte[] b = raw(path);
        try {
            return b == null ? null : new JSONObject(new String(b, "UTF-8"));
        } catch (Exception e) {
            return null;
        }
    }

    private byte[] raw(String path) {
        try {
            HttpURLConnection h = Tls.open(this, path);
            h.setConnectTimeout(3000);
            h.setReadTimeout(20000);
            try {
                return h.getResponseCode() == 200 ? read(h.getInputStream()) : null;
            } finally {
                h.disconnect();
            }
        } catch (Exception e) {
            return null;
        }
    }

    private static byte[] read(InputStream in) throws Exception {
        try (InputStream i = in) {
            ByteArrayOutputStream o = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = i.read(buf)) > 0) o.write(buf, 0, n);
            return o.toByteArray();
        }
    }

    private String version() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "";
        }
    }

    private TextView warning(String s, View.OnClickListener click, int topDp) {
        TextView w = text(s, 13, 0xFFFFB340);
        w.setPadding(dp(14), dp(12), dp(14), dp(12));
        w.setBackground(shape(0xFF3A2E14, 14));
        if (click != null) w.setOnClickListener(click);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2);
        p.topMargin = dp(topDp);
        w.setLayoutParams(p);
        return w;
    }

    @Override
    public void onBackPressed() {
        if (sub != null) {
            mainPage();
            return;
        }
        super.onBackPressed();
    }

    // ---- permissions ----

    // One row of the permissions page. ok: true granted, false missing, null unknown (the
    // system gives no way to read it, e.g. vendor background limits).
    private static class Perm {
        final String title, desc;
        final Boolean ok;
        final Runnable fix;

        Perm(String title, String desc, Boolean ok, Runnable fix) {
            this.title = title;
            this.desc = desc;
            this.ok = ok;
            this.fix = fix;
        }
    }

    private void open(Intent i, Intent fallback) {
        try {
            startActivity(i);
        } catch (Exception e) {
            try {
                if (fallback != null) startActivity(fallback);
            } catch (Exception ignored) {
                toast("无法打开设置页面，请到系统设置里手动查找 Music Relay");
            }
        }
    }

    private Intent appDetails() {
        return new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                android.net.Uri.parse("package:" + getPackageName()));
    }

    private List<Perm> permissions() {
        boolean agent = "agent".equals(role(this));
        List<Perm> l = new ArrayList<>();
        l.add(new Perm("通知使用权",
                agent ? "读取并控制本机的音乐 App（必需）" : "读取手机上正在播放的歌曲（必需）",
                hasAccess(), () -> {
                    Intent d = new Intent("android.settings.NOTIFICATION_LISTENER_DETAIL_SETTINGS")
                            .putExtra("android.provider.extra.NOTIFICATION_LISTENER_COMPONENT_NAME",
                                    new ComponentName(this, Listener.class).flattenToString());
                    open(d, new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS));
                }));
        l.add(new Perm("通知栏常驻",
                "允许通知，状态栏才会显示常驻通知和 上一曲 / 播放 / 下一曲 按钮",
                getSystemService(android.app.NotificationManager.class).areNotificationsEnabled(),
                () -> open(new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                        .putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName()), appDetails())));
        l.add(new Perm("不优化电池",
                "让系统不在后台冻结 Music Relay，" + (agent ? "播放器才能一直待命" : "镜像才不会中断"),
                getSystemService(android.os.PowerManager.class).isIgnoringBatteryOptimizations(getPackageName()),
                () -> open(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                                android.net.Uri.parse("package:" + getPackageName())),
                        new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))));
        l.add(new Perm("厂商后台限制",
                "系统无法检测，不开会被系统冻结、镜像时断时续。点此打开应用信息，然后："
                        + "vivo：电量 → 后台耗电管理 → 允许后台耗电；三星：电池 → 不受限制；"
                        + "小米：省电策略 → 无限制，并打开自启动",
                null, () -> open(appDetails(), null)));
        if (agent) {
            l.add(new Perm("显示在其他应用上层",
                    "QQ音乐、网易云要在后台打开歌曲链接，需要这项",
                    Settings.canDrawOverlays(this),
                    () -> open(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            android.net.Uri.parse("package:" + getPackageName())), appDetails())));
            l.add(new Perm("使用情况访问",
                    "判断前台是不是音乐 App，避免切歌时盖住你正在用的界面",
                    hasUsageAccess(),
                    () -> open(new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS), appDetails())));
            String cmd = "adb shell pm grant " + getPackageName() + " android.permission.DUMP";
            l.add(new Perm("SRC 检测（可选）",
                    "只能用电脑 adb 授权，点此复制命令：\n" + cmd,
                    checkSelfPermission(android.Manifest.permission.DUMP)
                            == android.content.pm.PackageManager.PERMISSION_GRANTED,
                    () -> {
                        getSystemService(android.content.ClipboardManager.class).setPrimaryClip(
                                android.content.ClipData.newPlainText("adb", cmd));
                        toast("已复制 adb 命令");
                    }));
        }
        return l;
    }

    // Required or fixable items that are still off; optional and unknown ones do not count.
    private int missingPermissions() {
        int n = 0;
        for (Perm p : permissions()) {
            if (Boolean.FALSE.equals(p.ok) && !p.title.contains("可选")) n++;
        }
        return n;
    }

    private void showPermissions() {
        sub = this::showPermissions;
        LinearLayout l = subPage("权限");
        TextView tip = text("点每一项会跳到对应的系统设置，开好后返回这里会自动刷新。", 13, DIM);
        tip.setPadding(0, dp(8), 0, dp(6));
        l.addView(tip);
        for (Perm p : permissions()) {
            LinearLayout r = column();
            r.setPadding(dp(18), dp(14), dp(18), dp(14));
            r.setBackground(shape(CARD, 18));
            ripple(r);
            LinearLayout top = new LinearLayout(this);
            top.setGravity(Gravity.CENTER_VERTICAL);
            top.addView(bold(p.title, 16, Color.WHITE), new LinearLayout.LayoutParams(0, -2, 1));
            top.addView(text(p.ok == null ? "去设置 ›" : p.ok ? "✓ 已开启" : "未开启 ›", 14,
                    p.ok == null ? DIM : p.ok ? GREEN : 0xFFFFB340));
            r.addView(top);
            TextView d = text(p.desc, 13, DIM);
            d.setPadding(0, dp(4), 0, 0);
            r.addView(d);
            r.setOnClickListener(v -> p.fix.run());
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
            lp.topMargin = dp(12);
            l.addView(r, lp);
        }
        ScrollView sc = new ScrollView(this);
        sc.addView(l);
        page(sc);
    }

    // ---- certificates ----

    private static final int PICK_SERVER = 41;

    private void pick(int req) {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                .setType("*/*");
        try {
            startActivityForResult(i, req);
        } catch (Exception e) {
            toast("没有可用的文件选择器");
        }
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (res != RESULT_OK || data == null || data.getData() == null) return;
        android.net.Uri uri = data.getData();
        if (req == PICK_SERVER) {
            EditText pass = field("证书密码", InputType.TYPE_CLASS_TEXT
                    | InputType.TYPE_TEXT_VARIATION_PASSWORD);
            new android.app.AlertDialog.Builder(this)
                    .setTitle("输入 .p12 证书的密码")
                    .setView(pass)
                    .setPositiveButton("导入", (d, w) -> {
                        try {
                            Tls.importServer(this, uri, pass.getText().toString());
                            restartAgent();
                            toast("已改用你的证书，手机需要重新配对");
                        } catch (Exception e) {
                            toast("导入失败：密码不对，或文件不是带私钥的 .p12");
                        }
                        refreshPage();
                    })
                    .setNegativeButton("取消", null)
                    .show();
        }
    }

    private void refreshPage() {
        if (sub != null) sub.run();
        else mainPage();
    }

    private void mainPage() {
        sub = null;
        if ("agent".equals(role(this))) showAgent();
        else if (prefs.getString("host", "").isEmpty() || prefs.getString("code", "").isEmpty()) showPair();
        else showClient();
    }

    // Header with a back arrow for secondary pages; returns the column to fill.
    private LinearLayout subPage(String title) {
        content = null;
        LinearLayout l = column();
        l.setPadding(dp(20), dp(20), dp(20), dp(28));
        LinearLayout head = new LinearLayout(this);
        head.setGravity(Gravity.CENTER_VERTICAL);
        TextView bk = bold("‹", 30, Color.WHITE);
        bk.setPadding(dp(4), 0, dp(16), dp(4));
        bk.setOnClickListener(v -> onBackPressed());
        head.addView(bk);
        head.addView(bold(title, 24, Color.WHITE));
        l.addView(head);
        return l;
    }

    private void restartAgent() {
        if (!AgentService.enabled(this)) return;
        stopService(new Intent(this, AgentService.class));
        ui.postDelayed(() -> startForegroundService(new Intent(this, AgentService.class)), 500);
    }

    // Player only: one switch. The phone needs no setting, pairing detects it.
    private void showTransport() {
        sub = this::showTransport;
        LinearLayout l = subPage("加密连接");
        String m = Tls.serverMode(this);
        TextView tip = text("打开后，手机和播放器之间的通信会加密。改动后手机需要重新配对一次（输入配对码即可），"
                + "不需要其他设置。", 13, DIM);
        tip.setPadding(0, dp(8), 0, dp(4));
        l.addView(tip);
        l.addView(option("关（默认）", "家里自用一般够了", Tls.OFF.equals(m), v -> setServerTls(Tls.OFF)));
        l.addView(option("开", "自动生成证书，什么都不用准备", Tls.AUTO.equals(m), v -> setServerTls(Tls.AUTO)));
        TextView adv = text(Tls.P12.equals(m) ? "● 正在使用你自己的证书（.p12）· 点此更换"
                : "高级：使用自己的证书（.p12，需含私钥）", 13, Tls.P12.equals(m) ? GREEN : DIM);
        adv.setPadding(dp(4), dp(22), dp(4), 0);
        adv.setOnClickListener(v -> pick(PICK_SERVER));
        l.addView(adv);
        ScrollView sc = new ScrollView(this);
        sc.addView(l);
        page(sc);
    }

    private void setServerTls(String m) {
        if (m.equals(Tls.serverMode(this))) return;
        Tls.setServerMode(this, m);
        restartAgent();
        toast("已" + (Tls.OFF.equals(m) ? "关闭" : "打开") + "加密，手机需要重新配对");
        ui.postDelayed(this::showTransport, 700);
    }

    // A selectable card: a dot shows the current choice.
    private View option(String title, String desc, boolean selected, View.OnClickListener click) {
        LinearLayout r = column();
        r.setPadding(dp(18), dp(14), dp(18), dp(14));
        r.setBackground(shape(selected ? 0xFF16301F : CARD, 18));
        ripple(r);
        r.addView(statusDot(title, selected ? GREEN : GREY, 16));
        TextView d = text(desc, 13, DIM);
        d.setPadding(0, dp(4), 0, 0);
        r.addView(d);
        r.setOnClickListener(click);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = dp(12);
        r.setLayoutParams(lp);
        return r;
    }
}
