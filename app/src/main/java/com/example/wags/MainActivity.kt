package com.example.wags

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.navigation.compose.rememberNavController
import com.example.wags.ui.navigation.WagsNavGraph
import com.example.wags.ui.theme.WagsTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            WagsTheme {
                val navController = rememberNavController()
                WagsNavGraph(navController = navController)
            }
        }
    }
}
