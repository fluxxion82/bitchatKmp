package com.bitchat.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.EaseInCubic
import androidx.compose.animation.core.EaseOutCubic
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.ModalBottomSheetValue
import androidx.compose.material.navigation.BottomSheetNavigator
import androidx.compose.material.navigation.ModalBottomSheetLayout
import androidx.compose.material.navigation.bottomSheet
import androidx.compose.material.rememberModalBottomSheetState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.navigation
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import com.bitchat.design.cardRoundShape
import com.bitchat.design.chat.ChatHeaderContent
import com.bitchat.design.chat.SidebarOverlay
import com.bitchat.design.core.DismissKeyboardOnTapOutside
import com.bitchat.design.util.IosBackGestureHandler
import com.bitchat.screens.battery.BatteryOptimizationScreen
import com.bitchat.screens.bluetooth.BluetoothDisabledScreen
import com.bitchat.screens.chat.ChatScreen
import com.bitchat.screens.location.LocationChannelsScreen
import com.bitchat.screens.location.LocationDisabledScreen
import com.bitchat.screens.location.LocationNotesScreen
import com.bitchat.screens.permissions.PermissionsErrorScreen
import com.bitchat.screens.permissions.PermissionsScreen
import com.bitchat.screens.settings.SettingsScreen
import com.bitchat.viewmodel.battery.BatteryOptimizationViewModel
import com.bitchat.viewmodel.chat.ChatViewModel
import com.bitchat.viewmodel.chat.DmViewModel
import com.bitchat.viewmodel.location.LocationChannelsViewModel
import com.bitchat.viewmodel.location.LocationDisabledViewModel
import com.bitchat.viewmodel.location.LocationNotesViewModel
import com.bitchat.viewmodel.main.MainViewModel
import com.bitchat.viewmodel.navigation.Back
import com.bitchat.viewmodel.navigation.BluetoothDisabled
import com.bitchat.viewmodel.navigation.Chat
import com.bitchat.viewmodel.navigation.LocationNotes
import com.bitchat.viewmodel.navigation.LocationServicesDisabled
import com.bitchat.viewmodel.navigation.Locations
import com.bitchat.viewmodel.navigation.OptimizationsSuggested
import com.bitchat.viewmodel.navigation.PermissionsRequest
import com.bitchat.viewmodel.navigation.Settings
import com.bitchat.viewmodel.permissions.PermissionsErrorViewModel
import com.bitchat.viewmodel.permissions.PermissionsViewModel
import com.bitchat.viewmodel.settings.SettingsViewModel
import com.bitchat.viewvo.chat.HeaderState
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.flow.receiveAsFlow
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

private fun isOnboardingRoute(routeString: String?): Boolean {
    if (routeString == null) return false
    return routeString.contains("Permissions") ||
            routeString.contains("BluetoothDisabled") ||
            routeString.contains("LocationServicesDisabled") ||
            routeString.contains("BatteryOptimization") ||
            routeString.contains("Welcome") ||
            routeString.contains("Onboard")
}

@OptIn(ExperimentalMaterial3Api::class)
@InternalCoroutinesApi
@ExperimentalFoundationApi
@Composable
fun BitchatGraph(mainViewModel: MainViewModel) {
    println("bitchat graph")
    val bottomSheetState = rememberModalBottomSheetState(
        initialValue = ModalBottomSheetValue.Hidden,
        skipHalfExpanded = true,
    )

    val bottomSheetNavigator = remember(bottomSheetState) {
        BottomSheetNavigator(sheetState = bottomSheetState)
    }

    val navController = rememberNavController(bottomSheetNavigator)

    LaunchedEffect(Unit) {
        mainViewModel.navigation.receiveAsFlow().collect { event ->
            println("main nav event: $event")
            when (event) {
                Back -> navController.navigateUp()
                is Chat -> navController.navigate(Routes.Chat(event.channel))
                Locations -> navController.navigate(Routes.Locations)
                is OptimizationsSuggested -> navController.navigate(Routes.BatteryOptimization.create(event.status))
                PermissionsRequest -> navController.navigate(Routes.Permissions)
                Settings -> navController.navigate(Routes.Settings)
                BluetoothDisabled -> navController.navigate(Routes.BluetoothDisabled) {
                    popUpTo(Routes.Permissions) {
                        inclusive = true
                    }
                    launchSingleTop = true
                }

                LocationServicesDisabled -> navController.navigate(Routes.LocationServicesDisabled) {
                    popUpTo(Routes.Permissions) {
                        inclusive = true
                    }
                    launchSingleTop = true
                }

                LocationNotes -> navController.navigate(Routes.LocationNotes)
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        ModalBottomSheetLayout(
            bottomSheetNavigator = bottomSheetNavigator,
            sheetShape = cardRoundShape,
            sheetBackgroundColor = MaterialTheme.colorScheme.background,
            modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.statusBars)
        ) {
            val headerState by mainViewModel.headerState.collectAsState()
            val dmViewModel: DmViewModel = koinViewModel()
            val dmState by dmViewModel.state.collectAsState()
            val currentBackStackEntry by navController.currentBackStackEntryAsState()

            Box(modifier = Modifier.fillMaxSize()) {
                Scaffold(
                    modifier = Modifier.fillMaxSize(),
                    topBar = {
                        if (!isOnboardingRoute(currentBackStackEntry?.destination?.route)) {
                            androidx.compose.foundation.layout.Column {
                                Box(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                                    ChatHeaderContent(
                                        selectedPrivatePeer = headerState.selectedPrivatePeer,
                                        currentChannel = headerState.currentChannel,
                                        nickname = headerState.nickname,
                                        favoritePeers = headerState.favoritePeers,
                                        favoriteRelationships = headerState.favoriteRelationships,
                                        peerFingerprints = headerState.peerFingerprints,
                                        peerSessionStates = headerState.peerSessionStates,
                                        geohashPeople = headerState.geohashPeople,
                                        peerNicknames = headerState.peerNicknames,
                                        selectedLocationChannel = headerState.selectedLocationChannel,
                                        selectedChannel = headerState.selectedChannel,
                                        teleported = headerState.teleported,
                                        permissionState = headerState.permissionState,
                                        locationServicesEnabled = headerState.locationServicesEnabled,
                                        powEnabled = headerState.powEnabled,
                                        powDifficulty = headerState.powDifficulty,
                                        isMining = headerState.isMining,
                                        torEnabled = headerState.torEnabled,
                                        torRunning = headerState.torRunning,
                                        torBootstrapPercent = headerState.torBootstrapPercent,
                                        onNicknameChange = mainViewModel::updateNickname,
                                        onToggleFavoritePeer = mainViewModel::toggleFavorite,
                                        setNickname = mainViewModel::updateNickname,
                                        onLeaveChannel = mainViewModel::leaveNamedChannel,
                                        onBackClick = mainViewModel::goBack,
                                        onSidebarClick = mainViewModel::toggleSidebar,
                                        onTripleClick = mainViewModel::handleTripleClick,
                                        onShowAppInfo = mainViewModel::showAppInfo,
                                        onLocationChannelsClick = mainViewModel::showLocationChannels,
                                        onLocationNotesClick = mainViewModel::showLocationNotes,
                                        openLatestUnreadPrivateChat = mainViewModel::openLatestUnreadDM,
                                        connectedPeers = headerState.connectedPeers,
                                        joinedChannels = headerState.joinedChannels,
                                        hasUnreadChannels = headerState.unreadChannelMessages,
                                        hasUnreadPrivateMessages = dmState.unreadPeers.isNotEmpty(),
                                        isConnected = headerState.isConnected,
                                        hasNotes = headerState.hasNotes,
                                        isCurrentChannelBookmarked = headerState.isCurrentChannelBookmarked,
                                        onToggleBookmark = mainViewModel::toggleBookmark
                                    )
                                }
                                HorizontalDivider(
                                    color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)
                                )
                            }
                        }
                    },
                    floatingActionButton = {},
                ) { paddingValues ->
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(paddingValues)
                            .windowInsetsPadding(WindowInsets.ime)
                    ) {
                        IosBackGestureHandler(
                            isEnabled = getPlatform() == Platform.IOS,
                            onBack = mainViewModel::goBack
                        ) {
                            DismissKeyboardOnTapOutside {
                                BitchatContent(
                                    navController = navController,
                                    mainViewModel = mainViewModel,
                                    dmViewModel = dmViewModel,
                                    headerState = headerState,
                                )
                            }
                        }
                    }
                }

                AnimatedVisibility(
                    visible = headerState.showSidebar,
                    enter = slideInHorizontally(
                        initialOffsetX = { it },
                        animationSpec = tween(300, easing = EaseOutCubic)
                    ) + fadeIn(animationSpec = tween(300)),
                    exit = slideOutHorizontally(
                        targetOffsetX = { it },
                        animationSpec = tween(250, easing = EaseInCubic)
                    ) + fadeOut(animationSpec = tween(250)),
                    modifier = Modifier.zIndex(2f)
                ) {
                    SidebarOverlay(
                        connectedPeers = headerState.connectedPeers,
                        joinedChannels = headerState.joinedChannels,
                        currentChannel = headerState.currentChannel,
                        selectedPrivatePeer = headerState.selectedPrivatePeer,
                        peerNicknames = headerState.peerNicknames,
                        peerDirect = headerState.peerDirect,
                        peerSessionStates = headerState.peerSessionStates,
                        favoritePeers = headerState.favoritePeers,
                        favoriteRelationships = headerState.favoriteRelationships,
                        hasUnreadPrivateMessages = dmState.unreadPeers,
                        unreadChannelMessages = headerState.unreadChannelMessages,
                        privateChats = dmState.privateChats.mapValues { it.value.size },
                        nickname = headerState.nickname,
                        selectedLocationChannel = headerState.selectedLocationChannel,
                        geohashPeople = headerState.geohashPeople,
                        isTeleported = headerState.teleported,
                        onChannelClick = { channel ->
                            mainViewModel.selectChannel(channel)
                            mainViewModel.toggleSidebar()
                        },
                        onLeaveChannel = mainViewModel::leaveNamedChannel,
                        onGeohashPersonTap = { person, geohash ->
                            mainViewModel.startGeohashDM(person, geohash)
                        },
                        onMeshPersonTap = { peerID ->
                            val nickname = headerState.peerNicknames[peerID] ?: peerID.take(12)
                            mainViewModel.startMeshDM(peerID, nickname)
                        },
                        onToggleFavorite = mainViewModel::toggleFavorite,
                        onDismiss = mainViewModel::toggleSidebar
                    )
                }
            }
        }
    }
}

@InternalCoroutinesApi
@ExperimentalFoundationApi
@Composable
fun BitchatContent(
    navController: NavHostController,
    mainViewModel: MainViewModel,
    dmViewModel: DmViewModel,
    headerState: HeaderState
) =
    NavHost(
        modifier = Modifier,
        navController = navController,
        startDestination = Routes.Home,
        enterTransition = {
            slideInHorizontally(
                initialOffsetX = { fullWidth -> fullWidth },
                animationSpec = tween(durationMillis = 300)
            )
        },
        exitTransition = {
            slideOutHorizontally(
                targetOffsetX = { fullWidth -> -fullWidth },
                animationSpec = tween(durationMillis = 300)
            )
        },
        popEnterTransition = {
            slideInHorizontally(
                initialOffsetX = { -it },
                animationSpec = tween(300)
            )
        },
        popExitTransition = {
            slideOutHorizontally(
                targetOffsetX = { it },
                animationSpec = tween(300)
            )
        },
    ) {
        onboardingGraph(navController)

        homeGraph(navController, dmViewModel, mainViewModel, headerState)
    }

fun NavGraphBuilder.onboardingGraph(navController: NavHostController) {
    navigation<Routes.Welcome>(startDestination = Routes.Permissions) {
        composable<Routes.Permissions> {
            val viewModel: PermissionsViewModel = koinViewModel()
            PermissionsScreen(navController, viewModel)
        }

        composable<Routes.PermissionsError> { backStackEntry ->
            val permissionsError: Routes.PermissionsError = backStackEntry.toRoute()
            val viewModel: PermissionsErrorViewModel = koinViewModel(
                parameters = { parametersOf(permissionsError.deniedPermissions) }
            )
            PermissionsErrorScreen(viewModel)
        }

        composable<Routes.BluetoothDisabled> {
            BluetoothDisabledScreen(navController)
        }

        composable<Routes.LocationServicesDisabled> {
            val viewModel: LocationDisabledViewModel = koinViewModel()
            LocationDisabledScreen(viewModel)
        }

        composable<Routes.BatteryOptimization> { backStackEntry ->
            val batteryOptimization: Routes.BatteryOptimization = backStackEntry.toRoute()
            val viewModel: BatteryOptimizationViewModel = koinViewModel()
            BatteryOptimizationScreen(batteryOptimization.status, viewModel)
        }
    }
}

fun NavGraphBuilder.homeGraph(
    navController: NavHostController,
    dmViewModel: DmViewModel,
    mainViewModel: MainViewModel,
    headerState: HeaderState,
) {
    navigation<Routes.Home>(startDestination = Routes.Chat("")) {
        composable<Routes.Chat> {
            val viewModel: ChatViewModel = koinViewModel()
            ChatScreen(
                viewModel = viewModel,
                dmViewModel = dmViewModel,
                selectedPrivatePeer = headerState.selectedPrivatePeer,
                peerNicknames = headerState.peerNicknames
            )
        }

        bottomSheet<Routes.Settings> {
            val viewModel: SettingsViewModel = koinViewModel()
            SettingsScreen(
                navController = navController,
                viewModel = viewModel
            )
        }

        bottomSheet<Routes.Locations> {
            val viewModel: LocationChannelsViewModel = koinViewModel()
            LocationChannelsScreen(viewModel = viewModel)
        }

        bottomSheet<Routes.LocationNotes> {
            val viewModel: LocationNotesViewModel = koinViewModel()
            LocationNotesScreen(mainViewModel = mainViewModel, viewModel = viewModel)
        }
    }
}
