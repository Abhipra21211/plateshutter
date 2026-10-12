package com.nish.plateshutter

import android.app.Activity
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val sp = getSharedPreferences("cfg", MODE_PRIVATE)
        val pad = (16 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        val sdk = Build.VERSION.SDK_INT
        val info = StringBuilder()
        info.append("Android ${Build.VERSION.RELEASE} (API $sdk)\n\n")
        info.append("TIMER mode: available\n")
        info.append(
            if (sdk >= 30) "WATCH mode (auto shoot when plate seen): available\n"
            else "WATCH mode: needs Android 11 or newer, not available here\n"
        )
        if (sdk >= 33) {
            info.append("\nAndroid 13+: if the accessibility switch is greyed out, open App info > menu > Allow restricted settings.\n")
        }
        root.addView(TextView(this).apply { text = info.toString(); textSize = 16f })

        fun field(label: String, key: String, def: String, num: Boolean): EditText {
            root.addView(TextView(this).apply { text = "\n$label" })
            val e = EditText(this).apply {
                setText(sp.getString(key, def))
                if (num) inputType = InputType.TYPE_CLASS_NUMBER
            }
            root.addView(e)
            return e
        }

        val kw = field("WATCH: words to look for (comma separated)", "kw", ClickService.DEF_KW, false)
        val min = field("WATCH: how many words must match", "min", "3", true)
        val sharp = field("WATCH: sharpness minimum (lower allows blur)", "sharp", "60", true)
        val pause = field("WATCH: pause after a shot (seconds)", "pause", "5", true)
        val timer = field("TIMER: seconds between shots", "timer", "10", true)
        val limit = field("Shot limit (stops by itself)", "limit", "50", true)

        root.addView(Button(this).apply {
            text = "Save settings"
            setOnClickListener {
                sp.edit()
                    .putString("kw", kw.text.toString())
                    .putString("min", min.text.toString())
                    .putString("sharp", sharp.text.toString())
                    .putString("pause", pause.text.toString())
                    .putString("timer", timer.text.toString())
                    .putString("limit", limit.text.toString())
                    .apply()
                Toast.makeText(this@MainActivity, "Saved", Toast.LENGTH_SHORT).show()
            }
        })
        root.addView(Button(this).apply {
            text = "Open Accessibility Settings"
            setOnClickListener { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        })
        val scroll = ScrollView(this)
        scroll.addView(root, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        setContentView(scroll)
    }
}
