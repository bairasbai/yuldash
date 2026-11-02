package com.yuldash.app

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.yuldash.app.data.ApiClient

/** Возврат из Settings/системного диалога обновляет разрешение даже у той же активной брони. */
@Composable
internal fun TripLocationPermissionEffect(
    bookingId: Int?, activeTrip: Ride?, language: AppLanguage, driverBookingId: Int?, session: Long,
    context: Context = LocalContext.current,
    permissionGranted: () -> Boolean = {
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
    },
    currentSession: () -> Long = { ApiClient.queueSessionGeneration() },
    start: (Int, AppLanguage, Boolean) -> Unit = { id, lang, driver -> TripLocationService.start(context, id, lang, driver) },
    stop: () -> Unit = { TripLocationService.stop(context) },
) {
    val owner = LocalLifecycleOwner.current
    var preciseGranted by remember(context) { mutableStateOf(permissionGranted()) }
    DisposableEffect(owner, context) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) preciseGranted = permissionGranted()
        }
        owner.lifecycle.addObserver(observer)
        preciseGranted = permissionGranted()
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(bookingId, activeTrip, language, driverBookingId, session, preciseGranted) {
        if (session == currentSession() && bookingId != null && activeTrip != null && preciseGranted) {
            start(bookingId, language, driverBookingId == bookingId)
        } else stop()
    }
}
