package com.vocaease.patient

import android.app.Application

class VocaEaseApplication : Application() {
    lateinit var container: AndroidAppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AndroidAppContainer(this)
    }
}
