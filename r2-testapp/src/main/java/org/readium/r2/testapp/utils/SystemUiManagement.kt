/*
 * Copyright 2021 Readium Foundation. All rights reserved.
 * Use of this source code is governed by the BSD-style license
 * available in the top-level LICENSE file of the project.
 */

package org.readium.r2.testapp.utils

import android.app.Activity
import android.os.Build
import android.view.View
import android.view.WindowInsets
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

/** Returns `true` if fullscreen or immersive mode is not set. */
private fun Activity.isSystemUiVisible(): Boolean {
    return this.window.decorView.systemUiVisibility and View.SYSTEM_UI_FLAG_FULLSCREEN == 0
}

@RequiresApi(Build.VERSION_CODES.R)
private fun View.isSystemUiVisible(): Boolean {
    return this.rootWindowInsets.isVisible(WindowInsetsCompat.Type.statusBars())
}

/** Enable fullscreen or immersive mode. */
fun Activity.hideSystemUi() {
    (this as AppCompatActivity).supportActionBar?.hide()
    WindowInsetsControllerCompat(this.window, this.window.decorView).let { controller ->
        controller.hide(WindowInsetsCompat.Type.statusBars() or WindowInsetsCompat.Type.navigationBars())
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }
}

/** Disable fullscreen or immersive mode. */
fun Activity.showSystemUi() {
    (this as AppCompatActivity).supportActionBar?.show()
    WindowInsetsControllerCompat(
        this.window,
        this.window.decorView
    ).show(WindowInsetsCompat.Type.statusBars() or WindowInsetsCompat.Type.navigationBars())
}

/** Toggle fullscreen or immersive mode. */
fun Activity.toggleSystemUi(view: View) {
    if (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            view.isSystemUiVisible()
        } else {
            this.isSystemUiVisible()
        }
    ) {
        this.hideSystemUi()
    } else {
        this.showSystemUi()
    }
}

/** Set padding around view so that content doesn't overlap system UI */
fun View.padSystemUi(insets: WindowInsets, activity: Activity) =
    setPadding(
        insets.systemWindowInsetLeft,
        insets.systemWindowInsetTop + (activity as AppCompatActivity).supportActionBar!!.height,
        insets.systemWindowInsetRight,
        insets.systemWindowInsetBottom
    )

/** Clear padding around view */
fun View.clearPadding() =
    setPadding(0, 0, 0,0)
