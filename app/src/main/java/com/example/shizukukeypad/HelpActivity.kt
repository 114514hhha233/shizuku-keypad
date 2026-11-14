package com.example.shizukukeypad

import android.app.Activity
import android.os.Bundle
import android.widget.TextView

/** 常见问题与解决方案 */
class HelpActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_help)
        findViewById<TextView>(R.id.btnBack).setOnClickListener { finish() }
    }
}
