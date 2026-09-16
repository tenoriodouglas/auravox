package com.auravox.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.auravox.ui.screen.LibraryScreen
import com.auravox.ui.screen.MixerSheet
import com.auravox.ui.screen.ResultScreen
import com.auravox.ui.screen.StageScreen
import com.auravox.ui.screen.TakesScreen

@Composable
fun AuraVoxApp(vm: KaraokeViewModel, onNeedMic: (() -> Unit) -> Unit) {
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(vm.message) {
        vm.message?.let {
            snackbar.showSnackbar(it)
            vm.clearMessage()
        }
    }

    Box(Modifier.fillMaxSize()) {
        when (vm.screen) {
            KaraokeViewModel.Screen.LIBRARY -> LibraryScreen(vm, onNeedMic)
            KaraokeViewModel.Screen.STAGE -> StageScreen(vm)
            KaraokeViewModel.Screen.RESULT -> ResultScreen(vm)
            KaraokeViewModel.Screen.TAKES -> TakesScreen(vm)
        }

        if (vm.showMixer) {
            MixerSheet(vm) { vm.showMixer = false }
        }

        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter))
    }
}
