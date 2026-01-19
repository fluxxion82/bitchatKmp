package com.bitchat.iosdi

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.ui.window.ComposeUIViewController
import com.bitchat.screens.BitchatGraph
import com.bitchat.viewmodel.main.MainViewModel
import kotlinx.coroutines.InternalCoroutinesApi
import org.koin.compose.viewmodel.koinViewModel

@OptIn(ExperimentalFoundationApi::class, InternalCoroutinesApi::class)
fun MainViewController() = ComposeUIViewController {
    val mainViewModel: MainViewModel = koinViewModel()
    BitchatGraph(mainViewModel)
}
