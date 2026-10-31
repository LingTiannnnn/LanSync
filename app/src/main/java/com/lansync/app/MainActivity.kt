package com.lansync.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.WifiTethering
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.lansync.app.data.model.RemoteAppEntry
import com.lansync.app.data.transfer.DownloadInstallController
import com.lansync.app.ui.components.AppCategory
import com.lansync.app.ui.components.FeedbackBanner
import com.lansync.app.ui.components.FeedbackTone
import com.lansync.app.ui.components.FileListScreen
import com.lansync.app.ui.components.IncomingConnectionSheet
import com.lansync.app.ui.components.InitialScanOverlay
import com.lansync.app.ui.components.LanSyncSpinner
import com.lansync.app.ui.components.RemoteAppListScreen
import com.lansync.app.ui.components.SaveDialogState
import com.lansync.app.ui.components.SaveStatusDialog
import com.lansync.app.ui.components.SyncScreen
import com.lansync.app.ui.components.copyFileToSafDirectory
import com.lansync.app.ui.components.filterByCategory
import com.lansync.app.ui.components.mergeRemoteEntries
import com.lansync.app.ui.components.DeviceListScreen
import com.lansync.app.ui.components.EmptyState
import com.lansync.app.ui.components.DownloadProgressDialog
import com.lansync.app.ui.string.installFailureText
import com.lansync.app.ui.theme.LanSyncTheme
import com.lansync.app.ui.viewmodel.MainViewModel
import com.lansync.app.ui.viewmodel.UiState
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    // FGS 在 MainViewModel.init 即自启，没有「用户点启动同步」的时机可挂，故请求只能落在 Activity 创建点。
    // 拒绝时不重试、不阻断：服务照常运行，仅常驻通知不可见。
    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        requestNotificationPermission()
        setContent {
            LanSyncTheme {
                LanSyncApp()
            }
        }
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            == PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
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

    // 包名 → 应用名：文件页主标题显示应用名而不是包名（与其余列表一致）
    val appNames = remember(uiState.localApps, uiState.connectedDevices) {
        buildMap {
            uiState.localApps.forEach { put(it.packageName, it.appName) }
            uiState.connectedDevices.flatMap { it.appList }.forEach { put(it.packageName, it.appName) }
        }
    }

    // —— 顶栏「全选/取消全选」的状态源：远程分类与同步选中设备原本是页内私有状态，
    //    顶栏要按「当前可见列表是否全选」渲染开关，故一并上提到这里。 ——
    // 默认落「用户应用」；系统应用是少数场景，不放首屏
    var remoteCategoryOrdinal by rememberSaveable { mutableStateOf(AppCategory.USER.ordinal) }
    val remoteCategory = AppCategory.entries.getOrElse(remoteCategoryOrdinal) { AppCategory.USER }
    // 只保存 displayKey（String 可入 Bundle）；DeviceInfo 非 Parcelable，rememberSaveable 会在恢复时崩溃
    var syncDeviceKey by rememberSaveable { mutableStateOf<String?>(null) }
    // 文件页选择态：List<String>（ArrayList 可入 Bundle），Set 直接进 rememberSaveable 会崩
    var selectedFileNames by rememberSaveable { mutableStateOf(emptyList<String>()) }

    val remoteEntries = remember(uiState.connectedDevices) { mergeRemoteEntries(uiState.connectedDevices) }
    val remoteFiltered = remoteEntries.filterByCategory(remoteCategory)
    val remoteAllSelected = uiState.remoteAppSelections.isNotEmpty() &&
        remoteFiltered.isNotEmpty() &&
        remoteFiltered.all { it.app.packageName in uiState.remoteAppSelections }

    val syncDevice = uiState.connectedDevices.firstOrNull { it.displayKey == syncDeviceKey }
        ?: uiState.connectedDevices.firstOrNull()
    val syncVisibleUpdates = syncDevice?.let { device ->
        uiState.availableUpdates.filter { it.providerDevice.displayKey == device.displayKey }
    } ?: emptyList()
    val syncAllSelected = uiState.selectedUpdates.isNotEmpty() &&
        syncVisibleUpdates.isNotEmpty() &&
        syncVisibleUpdates.all { it.remoteApp.packageName in uiState.selectedUpdates }

    val filesAllSelected = selectedFileNames.isNotEmpty() &&
        uiState.downloadedFiles.isNotEmpty() &&
        uiState.downloadedFiles.all { it.fileName in selectedFileNames }

    // 开关只在「该页有勾选且可见列表非空」时出现；可见列表为空时无事可全选
    val selectionAllSelected: Boolean
    val onSelectionToggle: (() -> Unit)?
    when (selectedTab) {
        1 -> {
            selectionAllSelected = remoteAllSelected
            onSelectionToggle =
                if (uiState.remoteAppSelections.isNotEmpty() && remoteFiltered.isNotEmpty()) {
                    {
                        if (remoteAllSelected) viewModel.clearRemoteAppSelection()
                        else viewModel.selectRemoteApps(remoteFiltered.map { it.app.packageName })
                    }
                } else null
        }
        2 -> {
            selectionAllSelected = syncAllSelected
            onSelectionToggle =
                if (uiState.selectedUpdates.isNotEmpty() && syncVisibleUpdates.isNotEmpty()) {
                    {
                        if (syncAllSelected) viewModel.clearSelection()
                        else viewModel.selectUpdates(syncVisibleUpdates.map { it.remoteApp.packageName })
                    }
                } else null
        }
        3 -> {
            selectionAllSelected = filesAllSelected
            onSelectionToggle =
                if (selectedFileNames.isNotEmpty() && uiState.downloadedFiles.isNotEmpty()) {
                    {
                        selectedFileNames =
                            if (filesAllSelected) emptyList()
                            else uiState.downloadedFiles.map { it.fileName }
                    }
                } else null
        }
        else -> {
            selectionAllSelected = false
            onSelectionToggle = null
        }
    }

    // 横屏 / 刘海机型：displayCutout 会把内容与可点区域压到屏幕边缘之外。
    // 只取它的**水平分量**：顶栏自己消费 statusBars、底栏自己消费 navigationBars（都是垂直方向），
    // 垂直方向再叠一次就是双份 padding（CONVENTIONS §3 insets 陷阱）。
    val cutoutPadding = WindowInsets.displayCutout.asPaddingValues()
    val layoutDirection = LocalLayoutDirection.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(
                start = cutoutPadding.calculateStartPadding(layoutDirection),
                end = cutoutPadding.calculateEndPadding(layoutDirection),
            ),
    ) {
        LanSyncTopBar(
            isScanningApps = uiState.isScanningApps,
            selectedTab = selectedTab,
            isRefreshing = uiState.isFetchingRemoteApps,
            onRefreshDevices = { viewModel.refreshDevices() },
            selectionAllSelected = selectionAllSelected,
            onSelectionToggle = onSelectionToggle,
        )

        Box(modifier = Modifier.weight(1f)) {
            // tab 切换走共享动效时长（系统「移除动画」时退化为瞬时），不再硬跳
            Crossfade(
                targetState = selectedTab,
                modifier = Modifier.fillMaxSize().imePadding(),
                animationSpec = tween(durationMillis = LanSyncTheme.motion.standard),
            ) { tab ->
                LanSyncTabContent(
                    selectedTab = tab,
                    uiState = uiState,
                    viewModel = viewModel,
                    appNames = appNames,
                    remoteEntries = remoteEntries,
                    remoteCategory = remoteCategory,
                    onRemoteCategoryChange = { remoteCategoryOrdinal = it.ordinal },
                    syncDeviceKey = syncDeviceKey,
                    onSyncDeviceSelect = { syncDeviceKey = it },
                    selectedFileNames = selectedFileNames,
                    onSelectedFilesChange = { selectedFileNames = it },
                    saveDirLauncher = saveDirLauncher,
                    onSaveTargetFileNameChange = { saveTargetFileName = it },
                    onOpenUpdatesTab = { selectedTab = 2 },
                    modifier = Modifier.fillMaxSize(),
                )
            }

            LanSyncOverlays(
                uiState = uiState,
                viewModel = viewModel,
                saveDialogState = saveDialogState,
                onDismissSaveDialog = { saveDialogState = null },
            )
        }

        LanSyncBottomBar(
            selectedTab = selectedTab,
            onTabSelected = { tab ->
                selectedTab = tab
                if (tab == 3) viewModel.loadDownloadedFiles()
            },
        )
    }
}

/** 4 Tab 语境标题（与底栏一致，去掉页内大号「LanSync」）。 */
private fun tabTitleRes(selectedTab: Int): Int = when (selectedTab) {
    0 -> R.string.tab_devices
    1 -> R.string.tab_remote
    2 -> R.string.tab_sync
    else -> R.string.tab_files
}

/**
 * 顶栏不再有「语境切换」：左侧永远是页名（勾选前后不变），右侧动作按 tab 定格——
 * 设备页保留刷新；远程/同步页勾选时出现「全选/取消全选」开关（可见列表为空时不出现）；
 * 其余页无右上动作（刷新入口在页内摘要卡/设备行）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LanSyncTopBar(
    isScanningApps: Boolean,
    selectedTab: Int,
    isRefreshing: Boolean,
    onRefreshDevices: () -> Unit,
    selectionAllSelected: Boolean,
    onSelectionToggle: (() -> Unit)?,
) {
    val sp = LanSyncTheme.spacing
    TopAppBar(
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(tabTitleRes(selectedTab)),
                    style = MaterialTheme.typography.titleLarge,
                )
                if (isScanningApps) {
                    Spacer(Modifier.width(sp.space8))
                    LanSyncSpinner(
                        size = sp.topBarIndicator,
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
            if (onSelectionToggle != null) {
                TextButton(
                    onClick = onSelectionToggle,
                    modifier = Modifier.heightIn(min = sp.minTouchTarget),
                ) {
                    Text(
                        stringResource(
                            if (selectionAllSelected) R.string.action_deselect_all
                            else R.string.action_select_all
                        )
                    )
                }
            }
            if (selectedTab == 0) {
                RefreshActionButton(
                    contentDescription = stringResource(R.string.cd_refresh),
                    isRefreshing = isRefreshing,
                    onClick = onRefreshDevices,
                )
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.surface,
            titleContentColor = MaterialTheme.colorScheme.onSurface,
        ),
    )
}

/** 刷新按钮：点击后图标旋转一圈，进行中显示转圈，给用户明确触发反馈。 */
@Composable
private fun RefreshActionButton(
    contentDescription: String,
    isRefreshing: Boolean,
    onClick: () -> Unit,
) {
    val sp = LanSyncTheme.spacing
    val motion = LanSyncTheme.motion
    val rotation = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()

    IconButton(
        onClick = {
            onClick()
            if (!isRefreshing) {
                scope.launch {
                    rotation.animateTo(
                        targetValue = rotation.value + 360f,
                        animationSpec = tween(durationMillis = motion.refreshSpin, easing = LinearEasing),
                    )
                }
            }
        },
        enabled = !isRefreshing,
    ) {
        if (isRefreshing) {
            LanSyncSpinner(size = sp.iconXl, strokeWidth = sp.strokeThin)
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
        LanSyncNavTab(1, Icons.Default.Cloud, R.string.tab_remote),
        LanSyncNavTab(2, Icons.Default.Sync, R.string.tab_sync),
        LanSyncNavTab(3, Icons.Default.Folder, R.string.tab_files),
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
    appNames: Map<String, String>,
    remoteEntries: List<RemoteAppEntry>,
    remoteCategory: AppCategory,
    onRemoteCategoryChange: (AppCategory) -> Unit,
    syncDeviceKey: String?,
    onSyncDeviceSelect: (String) -> Unit,
    selectedFileNames: List<String>,
    onSelectedFilesChange: (List<String>) -> Unit,
    saveDirLauncher: androidx.activity.compose.ManagedActivityResultLauncher<android.net.Uri?, android.net.Uri?>,
    onSaveTargetFileNameChange: (String?) -> Unit,
    onOpenUpdatesTab: () -> Unit,
    modifier: Modifier = Modifier
) {
    when (selectedTab) {
        0 -> {
            DeviceListScreen(
                devices = uiState.discoveredDevices,
                isRunning = uiState.isRunning,
                serverPort = uiState.serverPort,
                isStarting = uiState.isStarting,
                isStopping = uiState.isStopping,
                connectionNotice = uiState.connectionNotice,
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
        1 -> RemoteTabContent(
            uiState, viewModel, remoteEntries, remoteCategory, onRemoteCategoryChange, modifier
        )
        2 -> SyncTabContent(uiState, viewModel, syncDeviceKey, onSyncDeviceSelect, modifier)
        3 -> FileTabContent(
            uiState = uiState,
            viewModel = viewModel,
            selectedFileNames = selectedFileNames,
            onSelectedFilesChange = onSelectedFilesChange,
            appNames = appNames,
            saveDirLauncher = saveDirLauncher,
            onSaveTargetFileNameChange = onSaveTargetFileNameChange,
            onOpenUpdatesTab = onOpenUpdatesTab,
            modifier = modifier,
        )
    }
}

@Composable
private fun RemoteTabContent(
    uiState: UiState,
    viewModel: MainViewModel,
    remoteEntries: List<RemoteAppEntry>,
    remoteCategory: AppCategory,
    onRemoteCategoryChange: (AppCategory) -> Unit,
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
            remoteEntries = remoteEntries,
            category = remoteCategory,
            onCategoryChange = onRemoteCategoryChange,
            selectedPackages = uiState.remoteAppSelections,
            isRefreshing = uiState.isFetchingRemoteApps,
            onToggleSelected = { viewModel.toggleRemoteAppSelection(it) },
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
    syncDeviceKey: String?,
    onSyncDeviceSelect: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    SyncScreen(
        connectedDevices = uiState.connectedDevices,
        syncDiffs = uiState.syncDiffs,
        availableUpdates = uiState.availableUpdates,
        selectedUpdates = uiState.selectedUpdates,
        selectedDeviceKey = syncDeviceKey,
        onSelectDevice = onSyncDeviceSelect,
        onRefreshDevice = { viewModel.refreshConnectedDevice(it) },
        onToggleSelected = { viewModel.toggleSelectedUpdate(it) },
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
    selectedFileNames: List<String>,
    onSelectedFilesChange: (List<String>) -> Unit,
    appNames: Map<String, String>,
    saveDirLauncher: androidx.activity.compose.ManagedActivityResultLauncher<android.net.Uri?, android.net.Uri?>,
    onSaveTargetFileNameChange: (String?) -> Unit,
    onOpenUpdatesTab: () -> Unit,
    modifier: Modifier = Modifier
) {
    FileListScreen(
        files = uiState.downloadedFiles,
        selectedFiles = selectedFileNames,
        onSelectedFilesChange = onSelectedFilesChange,
        appNames = appNames,
        onInstall = { viewModel.installDownloadedFile(it) },
        onSave = { fileName ->
            onSaveTargetFileNameChange(fileName)
            saveDirLauncher.launch(null)
        },
        onDelete = { viewModel.deleteDownloadedFiles(it) },
        onRefresh = { viewModel.loadDownloadedFiles() },
        onOpenUpdatesTab = onOpenUpdatesTab,
        modifier = modifier
    )
}

/**
 * 覆盖层：反馈横幅放在**同一个**底部宿主里。
 *
 * 此前 `installStatus` 与 `operationMessage` 各挂一个 `Box(BottomCenter)`，
 * 两条反馈同时存在时后组合的会压在前一条上面（两个字段彼此独立，共存是正常时序）。
 */
@Composable
private fun LanSyncOverlays(
    uiState: UiState,
    viewModel: MainViewModel,
    saveDialogState: SaveDialogState?,
    onDismissSaveDialog: () -> Unit,
) {
    uiState.currentDownloadProgress?.let { progress ->
            DownloadProgressDialog(
                progress = progress,
                installing = uiState.installStatus is DownloadInstallController.InstallStatus.Installing,
                onDismiss = { viewModel.clearDownloadProgress() },
                onCancel = { viewModel.cancelDownload() },
                onInstall = if (progress.status == DownloadInstallController.DownloadProgress.Status.COMPLETED) {
                    { viewModel.installLastDownloaded() }
                } else null
            )
    }

    val opMessage = uiState.operationMessage
    if (uiState.installStatus != null || !opMessage.isNullOrBlank()) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.BottomCenter,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                uiState.installStatus?.let { status ->
                    val installMotion = LanSyncTheme.motion
                    when (status) {
                        is DownloadInstallController.InstallStatus.Installing -> FeedbackBanner(
                            text = stringResource(R.string.install_installing, status.packageName),
                            tone = FeedbackTone.Working,
                            busy = true,
                        )
                        is DownloadInstallController.InstallStatus.Success -> FeedbackBanner(
                            text = stringResource(R.string.install_success, status.packageName),
                            tone = FeedbackTone.Success,
                            autoDismissMs = installMotion.feedbackAutoDismissMs,
                            onDismiss = { viewModel.clearInstallStatus() },
                        )
                        is DownloadInstallController.InstallStatus.Failed -> FeedbackBanner(
                            text = stringResource(
                                R.string.install_failed,
                                installFailureText(status.failure),
                            ),
                            tone = FeedbackTone.Failure,
                            dismissible = true,
                            onDismiss = { viewModel.clearInstallStatus() },
                        )
                    }
                }
                if (!opMessage.isNullOrBlank()) {
                    FeedbackBanner(
                        text = opMessage,
                        tone = FeedbackTone.Info,
                    )
                }
            }
        }
    }

    saveDialogState?.let { state ->
        SaveStatusDialog(
            state = state,
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

    // 首扫遮罩带逃生口：扫描长时间不返回时用户可以离开，之后在本地列表里手动重扫
    if (uiState.needsInitialScan && !uiState.initialScanDismissed) {
        InitialScanOverlay(onSkip = { viewModel.skipInitialScan() })
    }
}
