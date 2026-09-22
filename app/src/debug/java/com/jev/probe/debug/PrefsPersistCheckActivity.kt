package com.jev.probe.debug

import android.os.Bundle
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import com.jev.probe.core.Prefs

/**
 * Debug-build only (src/debug — never in a release APK).
 *
 * Proves on a real device that a value written through Prefs survives being
 * killed hard, which is the whole point of writing with commit() instead of
 * apply(). Driven from adb:
 *
 *   adb shell am start -n <pkg>/com.jev.probe.debug.PrefsPersistCheckActivity --es v sk-xxx
 *   adb shell am force-stop <pkg>
 *   adb shell run-as <pkg> cat shared_prefs/jev_assistant.xml
 */
class PrefsPersistCheckActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val v = intent?.getStringExtra("v") ?: ""
        val clear = intent?.getBooleanExtra("clear", false) ?: false
        val p = Prefs(this)
        if (clear) p.judgeKey = ""      // wipe a test key back out
        else if (v.isNotBlank()) p.judgeKey = v
        val back = p.judgeKey
        Log.i(TAG, "persist-check wrote.len=${v.length} readBack.len=${back.length} " +
            "match=${back == v} autoAnalyze=${p.autoAnalyze} overlayW=${p.overlayWidth}")
        finish()
    }

    private companion object { const val TAG = "JEVASSIST" }
}
