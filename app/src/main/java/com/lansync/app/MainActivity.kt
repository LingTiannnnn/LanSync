package com.lansync.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.lansync.app.data.connection.ConnectionManager
import com.lansync.app.data.model.IncomingConnectRequest
import com.lansync.app.data.repository.AppRepository
import com.lansync.app.ui.components.*
import com.lansync.app.ui.theme.LanSyncTheme
import com.lansync.app.ui.viewmodel.MainViewModel
import kotlinx.coroutines.delay

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

    Box(Modifier.fillMaxSize()) {
        Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(text = "LanSync", fontWeight = FontWeight.Bold)
                        if (uiState.isScanningApps) {
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
                        0 -> {}
                        1 -> {}
                        2 -> {
                            IconButton(onClick = { viewModel.refreshDevices() }) {
                                Icon(Icons.Default.Refresh, contentDescription = "刷新远程应用")
                            }
                        }
                        3 -> {
                            IconButton(onClick = { viewModel.refreshDevices() }) {
                                Icon(Icons.Default.Refresh, contentDescription = "刷新同步")
                            }
                        }
                        4 -> {
                            IconButton(onClick = { viewModel.loadDownloadedFiles() }) {
                                Icon(Icons.Default.Refresh, contentDescription = "刷新文件")
                            }
                        }
                    }
                }
            )
        },
        bottomBar = {
            NavigationBar(
                containerColor = MaterialTheme.colorScheme.background
            ) {
                NavigationBarItem(
                    icon = { Icon(Icons.Default.WifiTethering, contentDescription = "设备") },
                    label = { Text("设备") },
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 }
                )
                NavigationBarItem(
                    icon = { Icon(Icons.Default.Apps, contentDescription = "本地应用") },
                    label = { Text("本地应用") },
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 }
                )
                NavigationBarItem(
                    icon = { Icon(Icons.Default.Cloud, contentDescription = "远程应用") },
                    label = { Text("远程应用") },
                    selected = selectedTab == 2,
                    onClick = { selectedTab = 2 }
                )
                NavigationBarItem(
                    icon = { Icon(Icons.Default.Sync, contentDescription = "同步") },
                    label = { Text("同步") },
                    selected = selectedTab == 3,
                    onClick = { selectedTab = 3 }
                )
                NavigationBarItem(
                    icon = { Icon(Icons.Default.Folder, contentDescription = "文件") },
                    label = { Text("文件") },
                    selected = selectedTab == 4,
                    onClick = {
                        selectedTab = 4
                        viewModel.loadDownloadedFiles()
                    }
                )
            }
        },
        floatingActionButton = { }
    ) { paddingValues ->
        when (selectedTab) {
            0 -> {
                DeviceListScreen(
                    devices = uiState.discoveredDevices,
                    isRunning = uiState.isRunning,
                    isScanningApps = uiState.isScanningApps,
                    serverPort = uiState.serverPort,
                    onToggleRunning = { viewModel.toggleRunning() },
                    onForceStartSync = { viewModel.forceStartSync() },
                    onRefresh = { viewModel.refreshDevices() },
                    onConnect = { viewModel.connectDevice(it) },
                    onDisconnect = { viewModel.disconnectDevice(it) },
                    modifier = Modifier.padding(paddingValues)
                )
            }
            1 -> {
                if (uiState.isScanningApps && uiState.localApps.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(paddingValues),
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
                        modifier = Modifier.padding(paddingValues)
                    )
                }
            }
            2 -> {
                if (uiState.connectedDevices.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(paddingValues),
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
                        onToggleSelected = { viewModel.toggleRemoteAppSelection(it) },
                        onSelectAll = { viewModel.selectAllRemoteApps(it) },
                        onClearSelection = { viewModel.clearRemoteAppSelection() },
                        onPullApp = { viewModel.pullRemoteApp(it) },
                        onPullSelected = { viewModel.pullSelectedRemoteApps(it) },
                        onRefresh = { viewModel.refreshDevices() },
                        modifier = Modifier.padding(paddingValues)
                    )
                }
            }
            3 -> {
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
                    modifier = Modifier.padding(paddingValues)
                )
            }
            4 -> {
                FileListScreen(
                    files = uiState.downloadedFiles,
                    onInstall = { viewModel.installDownloadedFile(it) },
                    onDelete = { viewModel.deleteDownloadedFiles(it) },
                    onRefresh = { viewModel.loadDownloadedFiles() },
                    modifier = Modifier.padding(paddingValues)
                )
            }
        }

        if (uiState.currentDownloadProgress != null) {
            DownloadProgressDialog(
                progress = uiState.currentDownloadProgress!!,
                onDismiss = { viewModel.clearDownloadProgress() },
                onInstall = if (uiState.currentDownloadProgress?.status == AppRepository.DownloadProgress.Status.COMPLETED) {
                    { viewModel.installLastDownloaded() }
                } else null
            )
        }

        if (uiState.installStatus != null) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
                contentAlignment = Alignment.BottomCenter
            ) {
                InstallStatusSnackbar(
                    status = uiState.installStatus!!,
                    onDismiss = { viewModel.clearInstallStatus() }
                )
            }
        }

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

        if (uiState.needsInitialScan) {
            InitialScanOverlay()
        }
    }
    }
}

@Composable
fun InitialScanOverlay() {
    Box(
        modifier = Modifier
            .fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Surface(
            modifier = Modifier
                .fillMaxSize(),
            color = MaterialTheme.colorScheme.background.copy(alpha = 0.95f)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Icon(
                    imageVector = Icons.Default.PhoneAndroid,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(64.dp)
                )
                Spacer(Modifier.height(24.dp))
                Text(
                    text = "正在扫描本地应用列表",
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onBackground
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    text = "首次使用需要扫描设备上安装的所有应用\n请稍候，此过程可能需要一些时间",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(32.dp))
                LinearProgressIndicator(
                    modifier = Modifier
                        .fillMaxWidth(0.7f)
                        .height(6.dp),
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.height(24.dp))
                Text(
                    text = "扫描完成后将自动启动同步服务",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline
                )
            }
        }
    }
}

@Composable
fun IncomingConnectionDialog(
    request: IncomingConnectRequest,
    onAccept: () -> Unit,
    onReject: () -> Unit,
    onDismiss: () -> Unit
) {
    var remainingSeconds by remember { mutableIntStateOf(ConnectionManager.REQUEST_TIMEOUT_MS.toInt() / 1000) }

    LaunchedEffect(request.requestId) {
        while (remainingSeconds > 0) {
            delay(1000L)
            remainingSeconds--
        }
        if (remainingSeconds <= 0) {
            onReject()
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Default.Link, null, tint = MaterialTheme.colorScheme.primary) },
        title = { Text("收到连接请求") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer,
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.PhoneAndroid, null, tint = MaterialTheme.colorScheme.onPrimaryContainer)
                        Spacer(Modifier.width(8.dp))
                        Column {
                            Text(request.requesterName, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Medium)
                            Text("${request.requesterIp}:${request.requesterPort}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }

                Surface(
                    color = if (remainingSeconds <= 5) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surfaceVariant,
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Box(modifier = Modifier.padding(12.dp), contentAlignment = Alignment.Center) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (remainingSeconds <= 5) {
                                Icon(Icons.Default.Warning, null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(4.dp))
                            } else {
                                Icon(Icons.Default.Schedule, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(4.dp))
                            }
                            Text(
                                text = "${remainingSeconds}秒后自动拒绝",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium,
                                color = if (remainingSeconds <= 5) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = onAccept) {
                Icon(Icons.Default.CheckCircle, null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
                Text("接受")
            }
        },
        dismissButton = {
            OutlinedButton(onClick = onReject) {
                Icon(Icons.Default.Cancel, null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
                Text("拒绝")
            }
        }
    )
}
