package com.nodepiazza

/**
 * Tracks whether the app UI is currently visible. [com.nodepiazza.ui.MainActivity] flips this
 * between onStart and onStop. Match notifications are suppressed while it's true — a user looking
 * at the app doesn't need to be pinged about a chat already sitting in their peer list.
 */
object AppForeground {
    @Volatile
    var isForeground: Boolean = false
}
