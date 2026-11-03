package app.musicrelay;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context c, Intent i) {
        if (!Intent.ACTION_BOOT_COMPLETED.equals(i.getAction())
                && !Intent.ACTION_MY_PACKAGE_REPLACED.equals(i.getAction())) return;
        String role = MainActivity.role(c);
        if ("agent".equals(role)) {
            if (AgentService.enabled(c)) c.startForegroundService(new Intent(c, AgentService.class));
        }
        // The phone side does not start itself (like Clash Meta with auto-start off).
    }
}
