package com.lansync.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
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
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
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
    // Material3 1.1 Scaffold 会在 layout{} 内 subcompose topBar；复杂 topBar 易触发 SlotTable 崩溃，
    // 故改用 Column 顶栏/内容/底栏三段布局，绕开该问题。
    var selectedTab by rememberSaveable { mutableStateOf(0) }
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

    val isSyncMultiSelect = selectedTab == 3 && uiState.selectedUpdates.isNotEmpty()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        LanSyncTopBar(
            isScanningApps = uiState.isScanningApps,
            selectedTab = selectedTab,
            syncSelectedCount = uiState.selectedUpdates.size,
            isSyncMultiSelect = isSyncMultiSelect,
            isRefreshing = uiState.isFetchingRemoteApps,
            onRefreshDevices = { viewModel.refreshDevices() },
            onLoadDownloadedFiles = { viewModel.loadDownloadedFiles() },
            onClearSyncSelection = { viewModel.clearSelection() },
        )

        Box(modifier = Modifier.weight(1f)) {
            LanSyncTabContent(
                selectedTab = selectedTab,
                uiState = uiState,
                viewModel = viewModel,
                saveTargetFileName = saveTargetFileName,
                saveDirLauncher = saveDirLauncher,
                onSaveTargetFileNameChange = { saveTargetFileName = it },
                modifier = Modifier.fillMaxSize(),
            )

            LanSyncOverlays(
                uiState = uiState,
                viewModel = viewModel,
                saveDialogState = saveDialogState,
                onDismissSaveDialog = { saveDialogState = null },
                paddingValues = PaddingValues(),
            )
        }

        LanSyncBottomBar(
            selectedTab = selectedTab,
            onTabSelected = { tab ->
                selectedTab = tab
                if (tab == 4) viewModel.loadDownloadedFiles()
            },
        )
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
    isRefreshing: Boolean,
    onRefreshDevices: () -> Unit,
    onLoadDownloadedFiles: () -> Unit,
    onClearSyncSelection: () -> Unit,
) {
    val sp = LanSyncTheme.spacing
    if (isSyncMultiSelect) {
        TopAppBar(
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
    } else {
        TopAppBar(
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
                    // 设备 / 远程 / 同步：刷新已连接设备的应用列表
                    0, 2, 3 -> {
                        RefreshActionButton(
                            contentDescription = stringResource(R.string.cd_refresh),
                            isRefreshing = isRefreshing,
                            onClick = onRefreshDevices,
                        )
                    }
                    // 文件：重新扫描本地下载缓存目录
                    4 -> {
                        RefreshActionButton(
                            contentDescription = stringResource(R.string.cd_refresh_files),
                            isRefreshing = false,
                            onClick = onLoadDownloadedFiles,
                        )
                    }
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = MaterialTheme.colorScheme.surface,
                titleContentColor = MaterialTheme.colorScheme.onSurface,
            ),
        )
    }
}

/** 刷新按钮：点击后图标旋转一圈，进行中显示转圈，给用户明确触发反馈。 */
@Composable
private fun RefreshActionButton(
    contentDescription: String,
    isRefreshing: Boolean,
    onClick: () -> Unit,
) {
    val sp = LanSyncTheme.spacing
    val rotation = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()

    IconButton(
        onClick = {
            onClick()
            if (!isRefreshing) {
                scope.launch {
                    rotation.animateTo(
                        targetValue = rotation.value + 360f,
                        animationSpec = tween(durationMillis = 700, easing = LinearEasing),
                    )
                }
            }
        },
        enabled = !isRefreshing,
    ) {
        if (isRefreshing) {
            CircularProgressIndicator(
                modifier = Modifier.size(sp.iconXl),
                strokeWidth = sp.strokeThin,
                color = MaterialTheme.colorScheme.primary,
            )
        } else {
            Icon(
                imageVector = Icons.Default.Refresh,
                contentDescription = contentDescription,
                modifier = Modifier.rotate(rotation.value),
            )
        }
    }
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
                updatableCount = uiState.availableUpdates.size,
                localAppCount = uiState.localApps.size,
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
