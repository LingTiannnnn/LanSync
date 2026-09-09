package com.lansync.app.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import com.lansync.app.R
import com.lansync.app.data.model.AppInfo
import com.lansync.app.data.model.UpdateInfo
import com.lansync.app.ui.theme.LanSyncTheme

enum class AppCategory { ALL, USER, SYSTEM }

private const val ITEM_TYPE_APP = "app_item"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppListScreen(
    localApps: List<AppInfo>,
    modifier: Modifier = Modifier
) {
    val sp = LanSyncTheme.spacing
    var searchQuery by remember { mutableStateOf("") }
    var category by remember { mutableStateOf(AppCategory.ALL) }

    val filteredApps by remember(searchQuery, category, localApps) {
        derivedStateOf {
            localApps.filter { app ->
                val matchesSearch = searchQuery.isEmpty() ||
                    app.appName.contains(searchQuery, ignoreCase = true) ||
                    app.packageName.contains(searchQuery, ignoreCase = true)
                val matchesCategory = when (category) {
                    AppCategory.ALL -> true
                    AppCategory.USER -> !app.isSystemApp
                    AppCategory.SYSTEM -> app.isSystemApp
                }
                matchesSearch && matchesCategory
            }
        }
    }

    val totalCount by remember(localApps) { derivedStateOf { localApps.size } }
    val userCount by remember(localApps) { derivedStateOf { localApps.count { !it.isSystemApp } } }
    val systemCount by remember(localApps) { derivedStateOf { localApps.count { it.isSystemApp } } }

    Column(modifier = modifier.fillMaxSize()) {
        SearchBar(
            query = searchQuery,
            onQueryChange = { searchQuery = it },
            placeholder = stringResource(R.string.search_local_placeholder)
        )

        CategoryTabs(
            selectedCategory = category,
            onCategoryChange = { category = it },
            totalCount = totalCount,
            userCount = userCount,
            systemCount = systemCount
        )

        Spacer(modifier = Modifier.height(sp.space4))

        SectionHeader(
            title = stringResource(R.string.local_section_title),
            icon = Icons.Default.PhoneAndroid,
            count = filteredApps.size
        )

        if (filteredApps.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(sp.space32),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        imageVector = Icons.Default.Inbox,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(sp.iconEmpty)
                    )
                    Spacer(modifier = Modifier.height(sp.space12))
                    Text(
                        text = when {
                            searchQuery.isNotEmpty() -> stringResource(R.string.local_empty_search, searchQuery)
                            category == AppCategory.USER -> stringResource(R.string.local_empty_user)
                            category == AppCategory.SYSTEM -> stringResource(R.string.local_empty_system)
                            else -> stringResource(R.string.local_empty_all)
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(horizontal = sp.space16, vertical = sp.space8),
                verticalArrangement = Arrangement.spacedBy(sp.space8)
            ) {
                items(
                    items = filteredApps,
                    key = { it.packageName },
                    contentType = { ITEM_TYPE_APP }
                ) { app ->
                    AppItem(appInfo = app)
                }
            }
        }
    }
}

@Composable
internal fun SearchBar(
    query: String,
    onQueryChange: (String) -> Unit,
    placeholder: String
) {
    val sp = LanSyncTheme.spacing
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = sp.space16, vertical = sp.space10),
        placeholder = { Text(placeholder, color = MaterialTheme.colorScheme.onSurfaceVariant) },
        leadingIcon = {
            Icon(Icons.Default.Search, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        },
        trailingIcon = if (query.isNotEmpty()) {
            {
                IconButton(onClick = { onQueryChange("") }) {
                    Icon(
                        Icons.Default.Close,
                        contentDescription = stringResource(R.string.cd_clear),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        } else null,
        singleLine = true,
        shape = RoundedCornerShape(sp.radiusLg),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = MaterialTheme.colorScheme.primary,
            unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)
        )
    )
}

@Composable
internal fun CategoryTabs(
    selectedCategory: AppCategory,
    onCategoryChange: (AppCategory) -> Unit,
    totalCount: Int,
    userCount: Int,
    systemCount: Int
) {
    val sp = LanSyncTheme.spacing
    ScrollableTabRow(
        selectedTabIndex = selectedCategory.ordinal,
        containerColor = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(horizontal = sp.space16),
        edgePadding = sp.none
    ) {
        TabItem(stringResource(R.string.category_all, totalCount), AppCategory.ALL, selectedCategory, onCategoryChange)
        TabItem(stringResource(R.string.category_user, userCount), AppCategory.USER, selectedCategory, onCategoryChange)
        TabItem(stringResource(R.string.category_system, systemCount), AppCategory.SYSTEM, selectedCategory, onCategoryChange)
    }
}

@Composable
internal fun TabItem(
    label: String,
    category: AppCategory,
    selected: AppCategory,
    onClick: (AppCategory) -> Unit
) {
    val isSelected = selected == category
    Tab(
        selected = isSelected,
        onClick = { onClick(category) },
        text = {
            Text(
                text = label,
                style = if (isSelected) MaterialTheme.typography.labelLarge
                else MaterialTheme.typography.labelMedium,
                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                maxLines = 1
            )
        },
        selectedContentColor = MaterialTheme.colorScheme.primary,
        unselectedContentColor = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

@Composable
fun AppItem(appInfo: AppInfo) {
    val sp = LanSyncTheme.spacing
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = sp.hairline)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(sp.space12),
            verticalAlignment = Alignment.CenterVertically
        ) {
            AppIcon(
                packageName = appInfo.packageName,
                size = sp.appIconLg
            )

            Spacer(modifier = Modifier.width(sp.space10))

            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = appInfo.appName,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    if (!appInfo.isExtractable) {
                        Spacer(modifier = Modifier.width(sp.space6))
                        Surface(
                            color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f),
                            shape = RoundedCornerShape(sp.radiusXs)
                        ) {
                            Text(
                                text = stringResource(R.string.badge_not_extractable),
                                modifier = Modifier.padding(horizontal = sp.space4, vertical = sp.hairline),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                    if (appInfo.isSplitApk) {
                        Spacer(modifier = Modifier.width(sp.space6))
                        Surface(
                            color = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.7f),
                            shape = RoundedCornerShape(sp.radiusXs)
                        ) {
                            Text(
                                text = stringResource(R.string.badge_split),
                                modifier = Modifier.padding(horizontal = sp.space4, vertical = sp.hairline),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onTertiaryContainer
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(sp.space2))

                Row(
                    horizontalArrangement = Arrangement.spacedBy(sp.space8),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = appInfo.versionName,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (appInfo.fileSize > 0) {
                        val sizeMb = remember(appInfo.fileSize) { appInfo.fileSize / (1024 * 1024) }
                        Text(
                            text = stringResource(R.string.size_mb, sizeMb),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.outline
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun UpdateItem(
    updateInfo: UpdateInfo,
    isSelected: Boolean,
    onToggleSelected: (String) -> Unit,
    onInstall: () -> Unit
) {
    val sp = LanSyncTheme.spacing
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected)
                MaterialTheme.colorScheme.primaryContainer
            else
                MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = sp.hairline)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(end = sp.space12),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Checkbox(
                checked = isSelected,
                onCheckedChange = { onToggleSelected(updateInfo.remoteApp.packageName) },
                modifier = Modifier.padding(start = sp.space4)
            )

            AppIcon(
                packageName = updateInfo.remoteApp.packageName,
                size = sp.appIconLg
            )

            Spacer(modifier = Modifier.width(sp.space10))

            Column(
                modifier = Modifier.weight(1f, fill = false)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = updateInfo.remoteApp.appName,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    Surface(
                        color = MaterialTheme.colorScheme.tertiaryContainer,
                        shape = RoundedCornerShape(sp.radiusSm)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(horizontal = sp.space4, vertical = sp.hairline)
                        ) {
                            Icon(
                                imageVector = Icons.Default.NewReleases,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onTertiaryContainer,
                                modifier = Modifier.size(sp.iconXxs)
                            )
                            Spacer(modifier = Modifier.width(sp.space2))
                            Text(
                                text = stringResource(R.string.badge_updatable),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onTertiaryContainer
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(sp.space2))

                Text(
                    text = if (updateInfo.localApp != null)
                        "${updateInfo.localApp.versionName} → ${updateInfo.remoteApp.versionName}"
                    else
                        updateInfo.remoteApp.versionName,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(top = sp.space2)
                ) {
                    Icon(
                        imageVector = Icons.Default.Cloud,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.secondary,
                        modifier = Modifier.size(sp.iconXs)
                    )
                    Spacer(modifier = Modifier.width(sp.space4))
                    Text(
                        text = updateInfo.providerDevice.deviceName,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.secondary,
                        maxLines = 1
                    )
                    if (updateInfo.remoteApp.fileSize > 0) {
                        Spacer(modifier = Modifier.width(sp.space6))
                        val sizeMb = remember(updateInfo.remoteApp.fileSize) {
                            updateInfo.remoteApp.fileSize / (1024 * 1024)
                        }
                        Text(
                            text = stringResource(R.string.size_mb, sizeMb),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.outline
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.width(sp.space8))

            Button(
                onClick = onInstall,
                modifier = Modifier.height(sp.buttonHeight),
                contentPadding = PaddingValues(horizontal = sp.space10),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary
                )
            ) {
                Icon(
                    imageVector = Icons.Default.Download,
                    contentDescription = null,
                    modifier = Modifier.size(sp.iconSm)
                )
                Spacer(modifier = Modifier.width(sp.space4))
                Text(stringResource(R.string.action_install), style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

@Composable
fun SectionHeader(
    title: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    count: Int,
    showSelectButtons: Boolean = false,
    areAllSelected: Boolean = false,
    onSelectAll: (() -> Unit)? = null,
    onClearSelection: (() -> Unit)? = null
) {
    val sp = LanSyncTheme.spacing
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = sp.space4),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(sp.iconLg)
            )
            Spacer(modifier = Modifier.width(sp.space6))
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.width(sp.space6))
            Surface(
                color = MaterialTheme.colorScheme.secondaryContainer,
                shape = RoundedCornerShape(sp.radiusMd)
            ) {
                Text(
                    text = count.toString(),
                    modifier = Modifier.padding(horizontal = sp.space8, vertical = sp.space2),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        if (showSelectButtons) {
            TextButton(
                onClick = if (areAllSelected) onClearSelection!! else onSelectAll!!,
                contentPadding = PaddingValues(horizontal = sp.space12)
            ) {
                Text(
                    stringResource(
                        if (areAllSelected) R.string.action_clear_selection else R.string.action_select_all
                    )
                )
            }
        }
    }
}

@Composable
fun EmptyStateCard(message: String) {
    val sp = LanSyncTheme.spacing
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = sp.space24),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(sp.space32),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                imageVector = Icons.Default.Inbox,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(sp.iconEmpty)
            )
            Spacer(modifier = Modifier.height(sp.space12))
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
