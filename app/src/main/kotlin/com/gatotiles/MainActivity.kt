package com.gatotiles

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.gatotiles.ui.GatoRoot

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { GatoRoot() }
    }

    override fun onStop() {
        super.onStop()
        GatoApp.app.preview.stop()
    }
}
