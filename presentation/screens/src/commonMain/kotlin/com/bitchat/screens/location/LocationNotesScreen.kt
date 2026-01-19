package com.bitchat.screens.location

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.bitchat.design.location.LocationNotesContent
import com.bitchat.viewmodel.location.LocationNotesViewModel
import com.bitchat.viewmodel.main.MainViewModel

@Composable
fun LocationNotesScreen(
    mainViewModel: MainViewModel,
    viewModel: LocationNotesViewModel,
) {
    val state by viewModel.state.collectAsState()

    DisposableEffect(Unit) {
        onDispose {
            mainViewModel.goBack()
        }
    }

    LocationNotesContent(
        state = state,
        onInputTextChange = viewModel::onInputTextChange,
        onSendNote = viewModel::onSendNote,
        onDismiss = {
            mainViewModel.goBack()
        }
    )
}
