package app.musicrelay;

import android.service.notification.NotificationListenerService;

// Holding notification access is what lets the agent read Apple Music's media session.
// It deliberately does not restart the player service: closing Music Relay from recents
// is the user's way to switch it off.
public class Listener extends NotificationListenerService {
}
