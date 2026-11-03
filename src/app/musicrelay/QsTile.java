package app.musicrelay;

import android.app.PendingIntent;
import android.content.Intent;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;

// The pull-down quick toggle. On the phone it switches mirror mode on/off; on the
// tablet it shows the agent is up and opens the app. Works like a VPN tile.
public class QsTile extends TileService {

    @Override
    public void onStartListening() {
        refresh();
    }

    @Override
    public void onClick() {
        boolean agent = "agent".equals(MainActivity.role(this));
        if (agent) {
            // Real on/off switch, no window. Long-press opens the app.
            boolean turnOn = !AgentService.enabled(this);
            AgentService.setEnabled(this, turnOn);
            if (turnOn) startForegroundService(new Intent(this, AgentService.class));
            else stopService(new Intent(this, AgentService.class));
            show(turnOn, true);
            return;
        }
        boolean turnOn = !RedirectService.isOn(this);
        if (turnOn && MainActivity.enabledApps(this).isEmpty()) {
            android.widget.Toast.makeText(this, "没有选择任何音乐 App，镜像无法工作",
                    android.widget.Toast.LENGTH_LONG).show();
            return;
        }
        // Best effort: vivo may ignore volume changes made while the app is in the background.
        if (turnOn) RedirectService.muteNow(this);
        startForegroundService(new Intent(this, RedirectService.class)
                .setAction(RedirectService.ACTION_TOGGLE));
        show(turnOn, false);
    }

    private void openApp() {
        Intent i = new Intent(this, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        try {
            startActivityAndCollapse(PendingIntent.getActivity(this, 0, i,
                    PendingIntent.FLAG_IMMUTABLE));
        } catch (Throwable t) {
            startActivity(i);
        }
    }

    private void refresh() {
        boolean agent = "agent".equals(MainActivity.role(this));
        show(agent ? AgentService.enabled(this) && AgentService.up : RedirectService.isOn(this), agent);
    }

    // Active (colored) when on, inactive (transparent) when off.
    private void show(boolean on, boolean agent) {
        Tile t = getQsTile();
        if (t == null) return;
        t.setState(on ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE);
        t.setLabel(agent ? "Relay 播放器" : "Relay 镜像");
        try {
            t.setSubtitle(on ? (agent ? "运行中" : "镜像中") : "已关闭");
        } catch (Throwable ignored) {
        }
        t.updateTile();
    }
}
