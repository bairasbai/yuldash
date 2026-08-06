package com.yuldash.shared

import androidx.compose.ui.window.ComposeUIViewController

// Мост в iOS. Swift-код (ContentView.swift) берёт этот UIViewController и показывает общий Compose-UI.
// В Xcode функция видна как MainViewControllerKt.MainViewController().
fun MainViewController() = ComposeUIViewController { App() }
