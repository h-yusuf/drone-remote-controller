package com.zora.drone

import android.net.ConnectivityManager
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.zora.drone.control.ControlState
import com.zora.drone.control.MotorTest
import com.zora.drone.net.DroneLink
import com.zora.drone.ui.FlyScreen
import com.zora.drone.ui.MotorTestScreen

class MainActivity : ComponentActivity() {
    private val state = ControlState()
    private val motorTest = MotorTest()
    private lateinit var link: DroneLink

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        link = DroneLink(getSystemService(ConnectivityManager::class.java), state, motorTest)
        setContent {
            if (motorTest.active) MotorTestScreen(motorTest, link)
            else FlyScreen(state, link) { state.stop(); motorTest.enter() }
        }
    }

    override fun onStart() {
        super.onStart()
        link.start()
    }

    // Background / screen off: leave motor test, disarm, thrust 0 x5 + motors off x3, stop sending.
    override fun onStop() {
        motorTest.exit()
        state.stop()
        link.stop()
        super.onStop()
    }
}
