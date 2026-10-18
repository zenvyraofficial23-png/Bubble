package com.example.bubble

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

// Pichle numbers ek saath daalne ke liye chhota sa dialog
class InputActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (16 * resources.displayMetrics.density).toInt()

        val info = TextView(this).apply {
            text = "Pichle numbers daalo: purana pehle, sabse naya aakhir me.\n" +
                    "Jaise: 9554902901\n" +
                    "(Ye sirf history me judenge, inka score nahi hoga.)"
            textSize = 15f
        }
        val edit = EditText(this).apply {
            hint = "9554902901"
            inputType = InputType.TYPE_CLASS_NUMBER
            textSize = 20f
        }
        val save = Button(this).apply {
            text = "SAVE"
            setOnClickListener {
                val digits = edit.text.toString().filter { it in '0'..'9' }
                if (digits.isEmpty()) {
                    Toast.makeText(this@InputActivity, "Number daalo", Toast.LENGTH_SHORT).show()
                } else {
                    startService(
                        Intent(this@InputActivity, BubbleService::class.java)
                            .putExtra("digits", digits)
                    )
                    finish()
                }
            }
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
            addView(info)
            addView(edit)
            addView(save)
        }
        setContentView(root)
    }
}
