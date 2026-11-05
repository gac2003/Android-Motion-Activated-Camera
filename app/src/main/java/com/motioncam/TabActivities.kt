package com.motioncam

import android.view.View

/*
 * The tab screens: the same screens as before, shown as tabs (with the tab bar, kept alive behind the other tabs).
 * Their own back arrow / Back go to Home instead of closing them, so a tab is still as it was left when you return.
 */

class ActivityTabActivity : com.motioncam.live.ActivityLogActivity() {
    override val asTab = true
    override fun setContentView(view: View) = super.setContentView(Nav.wrap(this, view, Nav.Tab.ACTIVITY))
    override fun onResume() { super.onResume(); Nav.tabShown(this, Nav.Tab.ACTIVITY) }
    override fun finish() { if (!isTaskRoot) Nav.leaveTab(this, Nav.Tab.ACTIVITY) else super.finish() }
}

class ClipsTabActivity : ClipsActivity() {
    override val asTab = true
    override fun onSelecting(on: Boolean) { window.decorView.findViewWithTag<View>("tabbar")?.visibility = if (on) View.GONE else View.VISIBLE }
    override fun setContentView(view: View) = super.setContentView(Nav.wrap(this, view, Nav.Tab.CLIPS))
    override fun onResume() { super.onResume(); Nav.tabShown(this, Nav.Tab.CLIPS); Nav.setClipsRemote(this, false) }
    override fun finish() { if (!isTaskRoot) Nav.leaveTab(this, Nav.Tab.CLIPS) else super.finish() }
}

class RemoteTabActivity : com.motioncam.remote.RemoteFoldersActivity() {
    override val asTab = true
    override fun onCreate(savedInstanceState: android.os.Bundle?) {
        super.onCreate(savedInstanceState)
        // the folder that was open when the app was last closed: open it again
        if (savedInstanceState == null && Nav.openFolder == null) Nav.lastFolder(this)?.let { id ->
            if (com.motioncam.remote.RemoteSources.get(this, id) != null)
                startActivity(android.content.Intent(this, RemoteClipsTabActivity::class.java).putExtra(ClipsActivity.EXTRA_REMOTE, id))
            else Nav.setLastFolder(this, null)
        }
    }
    override fun setContentView(view: View) = super.setContentView(Nav.wrap(this, view, Nav.Tab.CLIPS))
    override fun onResume() { super.onResume(); Nav.tabShown(this, Nav.Tab.CLIPS); Nav.setClipsRemote(this, true) }
    override fun finish() { if (!isTaskRoot) Nav.leaveTab(this, Nav.Tab.CLIPS) else super.finish() }
}

class SettingsTabActivity : SettingsActivity() {
    override val asTab = true
    override fun setContentView(view: View) = super.setContentView(Nav.wrap(this, view, Nav.Tab.SETTINGS))
    override fun onResume() { super.onResume(); Nav.tabShown(this, Nav.Tab.SETTINGS) }
    override fun finish() { if (!isTaskRoot) Nav.leaveTab(this, Nav.Tab.SETTINGS) else super.finish() }
}

/**
 * A remote folder opened in the Clips tab: like On this phone (tab bar, switch, count above the tiles), with the folder's
 * name to go back to your folders. Switching tabs and back returns to the open folder.
 */
class RemoteClipsTabActivity : ClipsActivity() {
    override val asTab = true
    override fun setContentView(view: View) = super.setContentView(Nav.wrap(this, view, Nav.Tab.CLIPS))
    override fun onSelecting(on: Boolean) { window.decorView.findViewWithTag<View>("tabbar")?.visibility = if (on) View.GONE else View.VISIBLE }
    override fun onCreate(savedInstanceState: android.os.Bundle?) { super.onCreate(savedInstanceState); Nav.openFolder = this }
    override fun onResume() {
        super.onResume(); Nav.tabShown(this, Nav.Tab.CLIPS); Nav.setClipsRemote(this, true)
        Nav.setLastFolder(this, intent.getStringExtra(EXTRA_REMOTE))   // reopened after a restart too
    }
    /** Back (or "Remote folders" again): to the list of folders, wherever it is in the stack. */
    override fun finish() {
        Nav.setLastFolder(this, null)
        super.finish()
        if (!isTaskRoot) Nav.open(this, RemoteTabActivity::class.java)
    }
    override fun onDestroy() { if (Nav.openFolder === this) Nav.openFolder = null; super.onDestroy() }
}
