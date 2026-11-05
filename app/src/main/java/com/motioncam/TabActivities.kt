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
    override fun onCreate(savedInstanceState: android.os.Bundle?) { Nav.clipsTop = java.lang.ref.WeakReference(this); super.onCreate(savedInstanceState) }
    override fun onSelecting(on: Boolean) { window.decorView.findViewWithTag<View>("tabbar")?.visibility = if (on) View.GONE else View.VISIBLE }
    override fun setContentView(view: View) = super.setContentView(Nav.wrap(this, view, Nav.Tab.CLIPS))
    override fun onResume() { super.onResume(); Nav.tabShown(this, Nav.Tab.CLIPS); Nav.setClipsRemote(this, false); Nav.clipsTop = java.lang.ref.WeakReference(this); Nav.clipsResumed(this) }
    override fun finish() { if (!isTaskRoot) Nav.leaveTab(this, Nav.Tab.CLIPS) else super.finish() }
}

class RemoteTabActivity : com.motioncam.remote.RemoteFoldersActivity() {
    override val asTab = true
    override fun onCreate(savedInstanceState: android.os.Bundle?) {
        Nav.clipsTop = java.lang.ref.WeakReference(this)
        super.onCreate(savedInstanceState)
        // the folder that was open when the app was last closed: open it again
        if (savedInstanceState == null && !Nav.folderOpen()) Nav.lastFolder(this)?.let { id ->
            if (com.motioncam.remote.RemoteSources.get(this, id) != null)
                startActivity(android.content.Intent(this, RemoteClipsTabActivity::class.java).putExtra(ClipsActivity.EXTRA_REMOTE, id))
                    .also { Nav.noWindowAnim(this) }
            else Nav.setLastFolder(this, null)
        }
    }
    override fun setContentView(view: View) = super.setContentView(Nav.wrap(this, view, Nav.Tab.CLIPS))
    override fun onResume() { super.onResume(); Nav.tabShown(this, Nav.Tab.CLIPS); Nav.setClipsRemote(this, true); Nav.clipsTop = java.lang.ref.WeakReference(this); Nav.clipsResumed(this) }
    override fun finish() { if (!isTaskRoot) Nav.leaveTab(this, Nav.Tab.CLIPS) else super.finish() }
}

class SettingsTabActivity : SettingsActivity() {
    override val asTab = true
    override fun setContentView(view: View) = super.setContentView(Nav.wrap(this, view, Nav.Tab.SETTINGS))
    override fun onResume() { super.onResume(); Nav.tabShown(this, Nav.Tab.SETTINGS) }
    override fun finish() { if (!isTaskRoot) Nav.leaveTab(this, Nav.Tab.SETTINGS) else super.finish() }
}

/**
 * Screens inside the Clips tab (a remote folder, the collections, one collection): like On this phone (tab bar, switch,
 * count above the tiles) with a "‹ name" row to go back. Returning to the tab shows the one that was open.
 */
abstract class ClipsInner : ClipsActivity(), ClipsSide {
    override val asTab = true
    override val onRemoteSide get() = sourceId != null
    override fun setContentView(view: View) = super.setContentView(Nav.wrap(this, view, Nav.Tab.CLIPS))
    override fun onSelecting(on: Boolean) { window.decorView.findViewWithTag<View>("tabbar")?.visibility = if (on) View.GONE else View.VISIBLE }
    override fun onCreate(savedInstanceState: android.os.Bundle?) {
        super.onCreate(savedInstanceState); Nav.innerOpened(this)
        if (savedInstanceState == null) Nav.slideContent(this, 1f)   // opened: its content slides in under the header
    }
    override fun onResume() {
        super.onResume(); Nav.tabShown(this, Nav.Tab.CLIPS); Nav.setClipsRemote(this, onRemoteSide)
        Nav.clipsTop = java.lang.ref.WeakReference(this); Nav.clipsResumed(this)
    }
    override fun finish() { super.finish(); Nav.innerClosed(this) }
}

/** A remote folder opened in the Clips tab (also reopened after a restart). */
class RemoteClipsTabActivity : ClipsInner() {
    override fun onResume() { super.onResume(); Nav.setLastFolder(this, intent.getStringExtra(EXTRA_REMOTE)) }
    override fun finish() { if (!Nav.quietly(this)) Nav.setLastFolder(this, null); super.finish() }
}

/** One collection's clips, in the Clips tab. */
class CollectionTabActivity : ClipsInner()

/** The collections (this phone's, or a remote folder's), in the Clips tab. */
class CollectionsTabActivity : CollectionsActivity(), ClipsSide {
    override val asTab = true
    override val onRemoteSide get() = sourceId != null
    override fun setContentView(view: View) = super.setContentView(Nav.wrap(this, view, Nav.Tab.CLIPS))
    override fun onCreate(savedInstanceState: android.os.Bundle?) {
        super.onCreate(savedInstanceState); Nav.innerOpened(this)
        if (savedInstanceState == null) Nav.slideContent(this, 1f)   // opened: its content slides in under the header
    }
    override fun onResume() {
        super.onResume(); Nav.tabShown(this, Nav.Tab.CLIPS); Nav.setClipsRemote(this, onRemoteSide)
        Nav.clipsTop = java.lang.ref.WeakReference(this); Nav.clipsResumed(this)
    }
    override fun finish() { super.finish(); Nav.innerClosed(this) }
}
