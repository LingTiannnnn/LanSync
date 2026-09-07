package com.lansync.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.lansync.app.data.repository.AppRepository
import com.lansync.app.ui.components.*
import com.lansync.app.ui.theme.LanSyncTheme
import com.lansync.app.ui.viewmodel.MainViewModel
import com.lansync.app.ui.viewmodel.UiState
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
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
    var selectedTab by remember { mutableIntStateOf(0) }
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
                LanSyncTopBar(
                    isScanningApps = uiState.isScanningApps,
                    selectedTab = selectedTab,
                    onRefreshDevices = { viewModel.refreshDevices() },
                    onLoadDownloadedFiles = { viewModel.loadDownloadedFiles() }
                )
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LanSyncTopBar(
    isScanningApps: Boolean,
    selectedTab: Int,
    onRefreshDevices: () -> Unit,
    onLoadDownloadedFiles: () -> Unit
) {
    TopAppBar(
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(text = "LanSync", fontWeight = FontWeight.Bold)
                if (isScanningApps) {
                    Spacer(Modifier.width(8.dp))
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.width(4.dp))
                    Text("扫描中...", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        },
        actions = {
            when (selectedTab) {
                2, 3 -> {
                    IconButton(onClick = onRefreshDevices) {
                        Icon(Icons.Default.Refresh, contentDescription = "刷新")
                    }
                }
                4 -> {
                    IconButton(onClick = onLoadDownloadedFiles) {
                        Icon(Icons.Default.Refresh, contentDescription = "刷新文件")
                    }
                }
            }
        }
    )
}

@Composable
private fun LanSyncBottomBar(
    selectedTab: Int,
    onTabSelected: (Int) -> Unit
) {
    NavigationBar(containerColor = MaterialTheme.colorScheme.background) {
        NavigationBarItem(
            icon = { Icon(Icons.Default.WifiTethering, contentDescription = "设备") },
            label = { Text("设备") },
            selected = selectedTab == 0,
            onClick = { onTabSelected(0) }
        )
        NavigationBarItem(
            icon = { Icon(Icons.Default.Apps, contentDescription = "本地应用") },
            label = { Text("本地应用") },
            selected = selectedTab == 1,
            onClick = { onTabSelected(1) }
        )
        NavigationBarItem(
            icon = { Icon(Icons.Default.Cloud, contentDescription = "远程应用") },
            label = { Text("远程应用") },
            selected = selectedTab == 2,
            onClick = { onTabSelected(2) }
        )
        NavigationBarItem(
            icon = { Icon(Icons.Default.Sync, contentDescription = "同步") },
            label = { Text("同步") },
            selected = selectedTab == 3,
            onClick = { onTabSelected(3) }
        )
        NavigationBarItem(
            icon = { Icon(Icons.Default.Folder, contentDescription = "文件") },
            label = { Text("文件") },
            selected = selectedTab == 4,
            onClick = { onTabSelected(4) }
        )
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
                onForceStartSync = { viewModel.forceStartSync() },
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
    if (uiState.isScanningApps && uiState.localApps.isEmpty()) {
        Box(
            modifier = Modifier.fillMaxSize().then(modifier),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator(modifier = Modifier.size(48.dp))
                Spacer(Modifier.height(16.dp))
                Text("正在扫描本地应用列表...", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    imageVector = Icons.Default.CloudOff,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(48.dp)
                )
                Spacer(Modifier.height(16.dp))
                Text("尚未连接任何设备", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(4.dp))
                Text("请在「设备」页面发现并连接设备后使用此功能", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
            }
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
            onInstall = if (uiState.currentDownloadProgress?.status == AppRepository.DownloadProgress.Status.COMPLETED) {
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

    // 传入连接请求对话框
    val pendingRequest = remember(uiState.incomingRequests) {
        uiState.incomingRequests.firstOrNull()
    }

    if (pendingRequest != null) {
        IncomingConnectionDialog(
            request = pendingRequest,
            onAccept = { viewModel.acceptIncomingRequest(pendingRequest.requestId) },
            onReject = { viewModel.rejectIncomingRequest(pendingRequest.requestId) },
            onDismiss = { viewModel.dismissIncomingRequest(pendingRequest.requestId) }
        )
    }

    // 初始扫描覆盖层
    if (uiState.needsInitialScan) {
        InitialScanOverlay()
    }
}
