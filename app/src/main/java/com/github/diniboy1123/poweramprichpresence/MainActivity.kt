package com.github.diniboy1123.poweramprichpresence

import android.app.Activity
import android.os.Bundle

// dummy activity, because we need one to be able to receive broadcasts
class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
    }
}
