package com.yuldash.app

import android.app.Application
import android.content.Context
import androidx.test.runner.AndroidJUnitRunner

/** Plain Application avoids YuldashApplication startup hooks; test Activities can still run.
 * Run on the isolated emulator with radios disabled; providers can initialize
 * before tests, so replacing Application alone is not network isolation.
 */
class StorageAuditRunner : AndroidJUnitRunner() {
    override fun newApplication(cl: ClassLoader, className: String, context: Context): Application =
        super.newApplication(cl, Application::class.java.name, context)
}
