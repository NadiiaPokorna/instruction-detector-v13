package com.example.intrusiondetector

import android.app.Application

class IntrusionApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer()
    }
}
