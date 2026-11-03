package com.motioncam.cloud

import android.app.job.JobParameters
import android.app.job.JobService

/** Lets Android resume uploads when the app isn't running (respects Wi-Fi / charging conditions). */
class CloudJobService : JobService() {
    override fun onStartJob(params: JobParameters): Boolean {
        CloudSync.runForJob(applicationContext) { more -> try { jobFinished(params, more) } catch (_: Exception) {} }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        CloudSync.requestStop()   // the current upload stops and resumes next time
        return true
    }
}
