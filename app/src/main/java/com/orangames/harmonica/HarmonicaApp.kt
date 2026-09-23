package com.orangames.harmonica

import android.app.Application
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import com.orangames.harmonica.data.SchemeStore
import com.orangames.harmonica.data.TakeStore

class HarmonicaApp : Application() {
    lateinit var store: TakeStore
        private set

    override fun onCreate() {
        super.onCreate()
        if (!Python.isStarted()) {
            Python.start(AndroidPlatform(this))
        }
        SchemeStore.load(this)
        store = TakeStore(this)
        store.refreshPending()
    }
}
