package com.yuldash.app

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsTopHeight
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ModalBottomSheetProperties
import androidx.compose.material3.SheetState
import androidx.compose.material3.contentColorFor
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance

/** Edge-to-edge: paint only the status inset, independently of scene/theme animation. */
@Composable
internal fun AppStatusBarProtection(
    statusBarInsets: WindowInsets = WindowInsets.statusBars,
    content: @Composable () -> Unit,
) {
    // Older windows draw the same brand through statusBarColor, outside the content area.
    if (Build.VERSION.SDK_INT < 35) { content(); return }
    Box(Modifier.fillMaxSize()) {
        content()
        Box(Modifier.fillMaxWidth().windowInsetsTopHeight(statusBarInsets).background(CanonGreenInk))
    }
}

/** A sheet owns another Window: keep its status consistent without a late appearance flip. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AppModalBottomSheet(
    onDismissRequest: () -> Unit,
    sheetState: SheetState = rememberModalBottomSheetState(),
    containerColor: Color = BottomSheetDefaults.ContainerColor,
    content: @Composable ColumnScope.() -> Unit,
) {
    val contentColor = contentColorFor(containerColor)
    // Material3's default for the existing opaque content colors; navigation remains adaptive.
    val darkNavigationIcons = contentColor.luminance() <= 0.5f
    val properties = remember(darkNavigationIcons) {
        ModalBottomSheetProperties(
            isAppearanceLightStatusBars = false,
            isAppearanceLightNavigationBars = darkNavigationIcons,
        )
    }
    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        sheetState = sheetState,
        containerColor = containerColor,
        contentColor = contentColor,
        properties = properties,
        content = content,
    )
}
