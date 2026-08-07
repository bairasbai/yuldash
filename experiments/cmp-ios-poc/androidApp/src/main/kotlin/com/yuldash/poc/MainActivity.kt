package com.yuldash.poc

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.yuldash.shared.App

// Android-хост проба-проекта: показывает ОБЩИЙ Compose-UI из модуля :shared.
// Ровно то же App() показывает и iOS (см. iosApp/ContentView.swift).
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            App()
        }
    }
}
