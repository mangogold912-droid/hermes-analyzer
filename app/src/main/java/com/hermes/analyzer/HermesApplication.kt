package com.hermes.analyzer

import android.app.Application
import android.content.Context

class HermesApplication : Application() {
    companion object {
        lateinit var appContext: Context
    }
    override fun onCreate() {
        super.onCreate()
        appContext = applicationContext
    }
}
