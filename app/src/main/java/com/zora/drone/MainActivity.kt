package com.zora.drone

import android.net.ConnectivityManager
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.zora.drone.control.ControlState
import com.zora.drone.net.DroneLink
import com.zora.drone.ui.FlyScreen

class MainActivity : ComponentActivity() {
    private val state = ControlState()
    private lateinit var link: DroneLink

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        link = DroneLink(getSystemService(ConnectivityManager::class.java), state)
        setContent { FlyScreen(state, link) }
    }

    override fun onStart() {
        super.onStart()
        link.start()
    }

    // Background / screen off: disarm, thrust 0 x5, stop sending.
    override fun onStop() {
        state.stop()
        link.stop()
        super.onStop()
    }
}
