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
