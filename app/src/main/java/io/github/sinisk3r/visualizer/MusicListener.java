package io.github.sinisk3r.visualizer;

import android.service.notification.NotificationListenerService;

/**
 * Exists only so the user can grant "notification access", which Android requires before an app may read other
 * apps' media sessions (MediaSessionManager.getActiveSessions, used by MusicCard). It overrides nothing: posted
 * notifications are ignored, never read, stored or sent.
 */
public final class MusicListener extends NotificationListenerService {
}
