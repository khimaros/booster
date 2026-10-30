package com.khimaros.booster

import android.util.AtomicFile
import android.util.Log
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.io.IOException

private const val LAYOUT_FILE = "layout.json"
private const val JSON_INDENT = 2
private const val TAG = "booster"

// the layout is one small json file, written atomically so a crash never leaves it half written
class Store(dir: File) {
    private val file = AtomicFile(File(dir, LAYOUT_FILE))

    fun load(): Layout? = try {
        if (file.baseFile.exists()) decode(String(file.readFully())) else null
    } catch (e: IOException) {
        Log.w(TAG, "layout unreadable", e)
        null
    }

    fun save(text: String) {
        val out = file.startWrite()
        try {
            out.write(text.toByteArray())
            file.finishWrite(out)
        } catch (e: IOException) {
            Log.w(TAG, "layout not saved", e)
            file.failWrite(out)
        }
    }

    companion object {
        fun encode(layout: Layout): String = layout.toJson().toString(JSON_INDENT)

        fun decode(text: String): Layout? = try {
            parseLayout(JSONObject(text))
        } catch (e: JSONException) {
            Log.w(TAG, "layout is not valid json", e)
            null
        }
    }
}
