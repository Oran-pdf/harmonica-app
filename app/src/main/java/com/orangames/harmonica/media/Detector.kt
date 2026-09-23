package com.orangames.harmonica.media

import com.chaquo.python.Python

object Detector {
    fun notesJson(wavPath: String, key: String): String {
        val module = Python.getInstance().getModule("detect_take")
        return module.callAttr("detect", wavPath, key).toString()
    }
}
