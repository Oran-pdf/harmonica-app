package com.orangames.harmonica

import android.app.Application
import com.orangames.harmonica.data.TakeStore

class HarmonicaApp : Application() {
    lateinit var store: TakeStore
        private set

    override fun onCreate() {
        super.onCreate()
        store = TakeStore(this)
        store.refreshPending()
    }
}
