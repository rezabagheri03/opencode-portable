package com.rs.ocportable

import android.app.Activity
import android.os.Bundle
import android.widget.TextView

class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(TextView(this).apply {
            text = "opencode-portable\n" +
                "abi=${android.os.Build.SUPPORTED_ABIS.first()}\n" +
                "sdk=${android.os.Build.VERSION.SDK_INT}\n" +
                "nativeLibraryDir=${applicationInfo.nativeLibraryDir}"
        })
    }
}
