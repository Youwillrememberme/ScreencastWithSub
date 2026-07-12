package com.subcast

import android.app.Application

/**
 * Application entry point. Holds the process-wide singletons that the UI and
 * media services reach for (HTTP server, DLNA controller, transcoder, etc.).
 *
 * Wired incrementally as modules land; kept minimal until then.
 */
class SubCastApp : Application() {

    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    companion object {
        @Volatile
        private var instance: SubCastApp? = null

        fun get(): SubCastApp =
            instance ?: error("SubCastApp not initialized")
    }
}
