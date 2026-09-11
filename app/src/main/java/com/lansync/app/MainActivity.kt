package com.lansync.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.viewmodel.compose.viewModel
import com.lansync.app.data.transfer.DownloadInstallController
import com.lansync.app.ui.components.*
import com.lansync.app.ui.theme.LanSyncTheme
import com.lansync.app.ui.viewmodel.MainViewModel
import com.lansync.app.ui.viewmodel.UiState
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            LanSyncTheme {
                LanSyncApp()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LanSyncApp(viewModel: MainViewModel = viewModel()) {
    val uiState by viewModel.uiState.collectAsState()
    var selectedTab by rememberSaveable { mutableIntStateOf(0) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var saveTargetFileName by remember { mutableStateOf<String?>(null) }
    var saveDialogState by remember { mutableStateOf<SaveDialogState?>(null) }

    val saveDirLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        val fileName = saveTargetFileName ?: return@rememberLauncherForActivityResult
        saveTargetFileName = null

        if (uri == null) {
            return@rememberLauncherForActivityResult
        }

        saveDialogState = SaveDialogState.Saving(fileName)

        scope.launch {
            val result = copyFileToSafDirectory(context, uri, fileName)
            saveDialogState = result
        }
    }

    Box(Modifier.fillMaxSize()) {
        Scaffold(
            topBar = {
                val isSyncMultiSelect =
                    selectedTab == 3 && uiState.selectedUpdates.isNotEmpty()
                val showIdentityStrip = selectedTab == 0 && !isSyncMultiSelect
                Column {
                    if (showIdentityStrip) {
                        IdentityStrip(
                            deviceName = android.os.Build.MODEL,
                            isRunning = uiState.isRunning,
                            serverPort = uiState.serverPort,
                            isStarting = uiState.isStarting,
                            isStopping = uiState.isStopping,
                            onToggleRunning = { viewModel.toggleRunning() },
                            updatableCount = uiState.availableUpdates.size,
                            connectedCount = uiState.connectedDevices.size,
                            localAppCount = uiState.localApps.size,
                        )
                    }
                    LanSyncTopBar(
                        isScanningApps = uiState.isScanningApps,
                        selectedTab = selectedTab,
                        syncSelectedCount = uiState.selectedUpdates.size,
                        isSyncMultiSelect = isSyncMultiSelect,
                        // IdentityStrip 已消费 statusBars；下方 TopAppBar 不得再叠一层
                        consumesStatusBars = showIdentityStrip,
                        onRefreshDevices = { viewModel.refreshDevices() },
                        onLoadDownloadedFiles = { viewModel.loadDownloadedFiles() },
                        onClearSyncSelection = { viewModel.clearSelection() },
                    )
                }
            },
            bottomBar = {
                LanSyncBottomBar(
                    selectedTab = selectedTab,
                    onTabSelected = { tab ->
                        selectedTab = tab
                        if (tab == 4) viewModel.loadDownloadedFiles()
                    }
                )
            },
            floatingActionButton = { }
        ) { paddingValues ->
            LanSyncTabContent(
                selectedTab = selectedTab,
                uiState = uiState,
                viewModel = viewModel,
                saveTargetFileName = saveTargetFileName,
                saveDirLauncher = saveDirLauncher,
                onSaveTargetFileNameChange = { saveTargetFileName = it },
                modifier = Modifier.padding(paddingValues)
            )

            LanSyncOverlays(
                uiState = uiState,
                viewModel = viewModel,
                saveDialogState = saveDialogState,
                onDismissSaveDialog = { saveDialogState = null },
                paddingValues = paddingValues
            )
        }
    }
}

/** 5 Tab 语境标题（与底栏一致，去掉页内大号「LanSync」）。 */
private fun tabTitleRes(selectedTab: Int): Int = when (selectedTab) {
    0 -> R.string.tab_devices
    1 -> R.string.tab_local
    2 -> R.string.tab_remote
    3 -> R.string.tab_sync
    else -> R.string.tab_files
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LanSyncTopBar(
    isScanningApps: Boolean,
    selectedTab: Int,
    syncSelectedCount: Int,
    isSyncMultiSelect: Boolean,
    consumesStatusBars: Boolean,
    onRefreshDevices: () -> Unit,
    onLoadDownloadedFiles: () -> Unit,
    onClearSyncSelection: () -> Unit,
) {
    val sp = LanSyncTheme.spacing
    // IdentityStrip 贴 statusBars 时，下方 TopAppBar 必须零 inset，避免双倍留白
    val topBarInsets =
        if (consumesStatusBars) WindowInsets(0, 0, 0, 0) else TopAppBarDefaults.windowInsets
    if (isSyncMultiSelect) {
        TopAppBar(
            windowInsets = topBarInsets,
            navigationIcon = {
                IconButton(onClick = onClearSyncSelection) {
                    Icon(
                        Icons.Default.Close,
                        contentDescription = stringResource(R.string.cd_close),
                    )
                }
            },
            title = {
                Text(
                    text = stringResource(R.string.selection_count_apps, syncSelectedCount),
                    style = MaterialTheme.typography.titleLarge,
                )
            },
            actions = {
                TextButton(onClick = onClearSyncSelection) {
                    Text(stringResource(R.string.action_clear_selection))
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = LanSyncTheme.containers.high,
                titleContentColor = MaterialTheme.colorScheme.onSurface,
                navigationIconContentColor = MaterialTheme.colorScheme.onSurface,
                actionIconContentColor = MaterialTheme.colorScheme.primary,
            ),
        )
        return
    }

    TopAppBar(
        windowInsets = topBarInsets,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(tabTitleRes(selectedTab)),
                    style = MaterialTheme.typography.titleLarge,
                )
                if (isScanningApps) {
                    Spacer(Modifier.width(sp.space8))
                    CircularProgressIndicator(
                        modifier = Modifier.size(sp.topBarIndicator),
                        strokeWidth = sp.strokeThin,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.width(sp.space4))
                    Text(
                        stringResource(R.string.top_bar_scanning),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        actions = {
            when (selectedTab) {
                0, 2, 3 -> {
                    IconButton(onClick = onRefreshDevices) {
                        Icon(
                            Icons.Default.Refresh,
                            contentDescription = stringResource(R.string.cd_refresh),
                        )
                    }
                }
                4 -> {
                    IconButton(onClick = onLoadDownloadedFiles) {
                        Icon(
                            Icons.Default.Refresh,
                            contentDescription = stringResource(R.string.cd_refresh_files),
                        )
                    }
                }
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.surface,
            titleContentColor = MaterialTheme.colorScheme.onSurface,
        ),
    )
}

private data class LanSyncNavTab(
    val index: Int,
    val icon: ImageVector,
    val labelRes: Int,
)

@Composable
private fun LanSyncBottomBar(
    selectedTab: Int,
    onTabSelected: (Int) -> Unit,
) {
    val tabs = listOf(
        LanSyncNavTab(0, Icons.Default.WifiTethering, R.string.tab_devices),
        LanSyncNavTab(1, Icons.Default.Apps, R.string.tab_local),
        LanSyncNavTab(2, Icons.Default.Cloud, R.string.tab_remote),
        LanSyncNavTab(3, Icons.Default.Sync, R.string.tab_sync),
        LanSyncNavTab(4, Icons.Default.Folder, R.string.tab_files),
    )
    // 与手势区同色，避免边缘到边缘布局下的小白条
    NavigationBar(
        containerColor = LanSyncTheme.containers.default,
        windowInsets = WindowInsets.navigationBars,
        modifier = Modifier.fillMaxWidth(),
    ) {
        tabs.forEach { tab ->
            val label = stringResource(tab.labelRes)
            NavigationBarItem(
                selected = selectedTab == tab.index,
                onClick = { onTabSelected(tab.index) },
                icon = { Icon(tab.icon, contentDescription = label) },
                label = { Text(label, style = MaterialTheme.typography.labelMedium) },
                colors = NavigationBarItemDefaults.colors(
                    indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                    selectedIconColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    selectedTextColor = MaterialTheme.colorScheme.onSurface,
                    unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                ),
            )
        }
    }
}

@Composable
private fun LanSyncTabContent(
    selectedTab: Int,
    uiState: UiState,
    viewModel: MainViewModel,
    saveTargetFileName: String?,
    saveDirLauncher: androidx.activity.compose.ManagedActivityResultLauncher<android.net.Uri?, android.net.Uri?>,
    onSaveTargetFileNameChange: (String?) -> Unit,
    modifier: Modifier = Modifier
) {
    when (selectedTab) {
        0 -> {
            DeviceListScreen(
                devices = uiState.discoveredDevices,
                isRunning = uiState.isRunning,
                isScanningApps = uiState.isScanningApps,
                serverPort = uiState.serverPort,
                isStarting = uiState.isStarting,
                isStopping = uiState.isStopping,
                operationMessage = uiState.operationMessage,
                connectionError = uiState.connectionError,
                onToggleRunning = { viewModel.toggleRunning() },
                onRefresh = { viewModel.refreshDevices() },
                onConnect = { viewModel.connectDevice(it) },
                onDisconnect = { viewModel.disconnectDevice(it) },
                onDismissError = { viewModel.dismissConnectionError() },
                modifier = modifier
            )
        }
        1 -> LocalTabContent(uiState, modifier)
        2 -> RemoteTabContent(uiState, viewModel, modifier)
        3 -> SyncTabContent(uiState, viewModel, modifier)
        4 -> FileTabContent(uiState, viewModel, saveTargetFileName, saveDirLauncher, onSaveTargetFileNameChange, modifier)
    }
}

@Composable
private fun LocalTabContent(
    uiState: UiState,
    modifier: Modifier = Modifier
) {
    val sp = LanSyncTheme.spacing
    if (uiState.isScanningApps && uiState.localApps.isEmpty()) {
        Box(
            modifier = Modifier.fillMaxSize().then(modifier),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator(modifier = Modifier.size(sp.iconEmpty))
                Spacer(Modifier.height(sp.space16))
                Text(
                    stringResource(R.string.local_scanning_list),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    } else {
        AppListScreen(
            localApps = uiState.localApps,
            modifier = modifier
        )
    }
}

@Composable
private fun RemoteTabContent(
    uiState: UiState,
    viewModel: MainViewModel,
    modifier: Modifier = Modifier
) {
    if (uiState.connectedDevices.isEmpty()) {
        Box(
            modifier = Modifier.fillMaxSize().then(modifier),
            contentAlignment = Alignment.Center,
        ) {
            EmptyState(
                icon = Icons.Default.CloudOff,
                title = stringResource(R.string.remote_empty_title),
                body = stringResource(R.string.remote_empty_hint),
                actionLabel = stringResource(R.string.cd_refresh),
                onAction = { viewModel.refreshDevices() },
            )
        }
    } else {
        RemoteAppListScreen(
            connectedDevices = uiState.connectedDevices,
            selectedPackages = uiState.remoteAppSelections,
            isRefreshing = uiState.isFetchingRemoteApps,
            onToggleSelected = { viewModel.toggleRemoteAppSelection(it) },
            onSelectAll = { viewModel.selectAllRemoteApps(it) },
            onClearSelection = { viewModel.clearRemoteAppSelection() },
            onPullApp = { viewModel.pullRemoteApp(it) },
            onPullSelected = { viewModel.pullSelectedRemoteApps(it) },
            onRefresh = { viewModel.refreshDevices() },
            modifier = modifier
        )
    }
}

@Composable
private fun SyncTabContent(
    uiState: UiState,
    viewModel: MainViewModel,
    modifier: Modifier = Modifier
) {
    SyncScreen(
        connectedDevices = uiState.connectedDevices,
        syncDiffs = uiState.syncDiffs,
        availableUpdates = uiState.availableUpdates,
        selectedUpdates = uiState.selectedUpdates,
        onRefreshDevice = { viewModel.refreshConnectedDevice(it) },
        onToggleSelected = { viewModel.toggleSelectedUpdate(it) },
        onSelectAll = { viewModel.selectAllUpdates() },
        onClearSelection = { viewModel.clearSelection() },
        onInstallUpdate = { viewModel.installSelectedUpdate(it) },
        onInstallSelectedUpdates = { viewModel.startBatchUpdate() },
        onRefreshDevices = { viewModel.refreshDevices() },
        modifier = modifier
    )
}

@Composable
private fun FileTabContent(
    uiState: UiState,
    viewModel: MainViewModel,
    saveTargetFileName: String?,
    saveDirLauncher: androidx.activity.compose.ManagedActivityResultLauncher<android.net.Uri?, android.net.Uri?>,
    onSaveTargetFileNameChange: (String?) -> Unit,
    modifier: Modifier = Modifier
) {
    FileListScreen(
        files = uiState.downloadedFiles,
        onInstall = { viewModel.installDownloadedFile(it) },
        onSave = { fileName ->
            onSaveTargetFileNameChange(fileName)
            saveDirLauncher.launch(null)
        },
        onDelete = { viewModel.deleteDownloadedFiles(it) },
        onRefresh = { viewModel.loadDownloadedFiles() },
        modifier = modifier
    )
}

@Composable
private fun LanSyncOverlays(
    uiState: UiState,
    viewModel: MainViewModel,
    saveDialogState: SaveDialogState?,
    onDismissSaveDialog: () -> Unit,
    paddingValues: PaddingValues
) {
    // 下载进度对话框
    if (uiState.currentDownloadProgress != null) {
        DownloadProgressDialog(
            progress = uiState.currentDownloadProgress!!,
            onDismiss = { viewModel.clearDownloadProgress() },
            onInstall = if (uiState.currentDownloadProgress?.status == DownloadInstallController.DownloadProgress.Status.COMPLETED) {
                { viewModel.installLastDownloaded() }
            } else null
        )
    }

    // 安装状态提示
    if (uiState.installStatus != null) {
        Box(
            modifier = Modifier.fillMaxSize().padding(paddingValues),
            contentAlignment = Alignment.BottomCenter
        ) {
            InstallStatusSnackbar(
                status = uiState.installStatus!!,
                onDismiss = { viewModel.clearInstallStatus() }
            )
        }
    }

    // 保存状态对话框
    if (saveDialogState != null) {
        SaveStatusDialog(
            state = saveDialogState!!,
            onDismiss = onDismissSaveDialog
        )
    }

    // 配对 Sheet（倒计时；返回键可关）
    val pendingRequest = remember(uiState.incomingRequests) {
        uiState.incomingRequests.firstOrNull()
    }
    if (pendingRequest != null) {
        IncomingConnectionSheet(
            request = pendingRequest,
            onAccept = { viewModel.acceptIncomingRequest(pendingRequest.requestId) },
            onReject = { viewModel.rejectIncomingRequest(pendingRequest.requestId) },
            onDismiss = { viewModel.dismissIncomingRequest(pendingRequest.requestId) },
        )
    }

    // 操作消息（启动/停止/刷新等短反馈）
    val opMessage = uiState.operationMessage
    if (!opMessage.isNullOrBlank()) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues),
            contentAlignment = Alignment.BottomCenter,
        ) {
            OperationMessageSnackbar(message = opMessage)
        }
    }

    // 初始扫描覆盖层
    if (uiState.needsInitialScan) {
        InitialScanOverlay()
    }
}

@Composable
private fun OperationMessageSnackbar(message: String, modifier: Modifier = Modifier) {
    val sp = LanSyncTheme.spacing
    Surface(
        modifier = modifier.padding(sp.space16),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.inverseSurface,
        contentColor = MaterialTheme.colorScheme.inverseOnSurface,
        shadowElevation = sp.space6,
    ) {
        Text(
            text = message,
            modifier = Modifier.padding(horizontal = sp.space16, vertical = sp.space12),
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}
