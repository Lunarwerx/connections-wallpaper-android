package com.anthonyla.paperize.connections

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * Where the browser lands after Connections sign-in (icu.connections.wallpaper://aegis-handoff).
 * It hands the code to [ConnectionsAccount], brings the app back to the front, and closes; it never
 * draws anything itself.
 */
@AndroidEntryPoint
class SignInReturnActivity : ComponentActivity() {
    @Inject
    lateinit var account: ConnectionsAccount

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) intent?.data?.let(account::finishSignIn)
        packageManager.getLaunchIntentForPackage(packageName)?.let { launch ->
            startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED))
        }
        finish()
    }
}
