package com.lansync.app.ui.icons

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.materialIcon
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes

/**
 * 本地图标补集：material-icons-core 未收录、此前由 icons-extended 提供的 26 个图标。
 * path 数据取自 Material 官方 24px filled 图形（与 @mui/icons-material 同源），经
 * Icons.Filled 扩展属性挂载，与库图标同一命名空间，调用点用法不变；新增图标照现有格式追加。
 */

private var _inbox: ImageVector? = null

val Icons.Filled.Inbox: ImageVector
    get() {
        _inbox?.let { return it }
        val icon = materialIcon(name = "Filled.Inbox") {
            addPath(
                pathData = addPathNodes("M19 3H4.99c-1.11 0-1.98.89-1.98 2L3 19c0 1.1.88 2 1.99 2H19c1.1 0 2-.9 2-2V5c0-1.11-.9-2-2-2m0 12h-4c0 1.66-1.35 3-3 3s-3-1.34-3-3H4.99V5H19z"),
                fill = SolidColor(Color.Black),
                pathFillType = PathFillType.NonZero,
            )
        }
        _inbox = icon
        return icon
    }

private var _link: ImageVector? = null

val Icons.Filled.Link: ImageVector
    get() {
        _link?.let { return it }
        val icon = materialIcon(name = "Filled.Link") {
            addPath(
                pathData = addPathNodes("M3.9 12c0-1.71 1.39-3.1 3.1-3.1h4V7H7c-2.76 0-5 2.24-5 5s2.24 5 5 5h4v-1.9H7c-1.71 0-3.1-1.39-3.1-3.1M8 13h8v-2H8zm9-6h-4v1.9h4c1.71 0 3.1 1.39 3.1 3.1s-1.39 3.1-3.1 3.1h-4V17h4c2.76 0 5-2.24 5-5s-2.24-5-5-5"),
                fill = SolidColor(Color.Black),
                pathFillType = PathFillType.NonZero,
            )
        }
        _link = icon
        return icon
    }

private var _error: ImageVector? = null

val Icons.Filled.Error: ImageVector
    get() {
        _error?.let { return it }
        val icon = materialIcon(name = "Filled.Error") {
            addPath(
                pathData = addPathNodes("M12 2C6.48 2 2 6.48 2 12s4.48 10 10 10 10-4.48 10-10S17.52 2 12 2m1 15h-2v-2h2zm0-4h-2V7h2z"),
                fill = SolidColor(Color.Black),
                pathFillType = PathFillType.NonZero,
            )
        }
        _error = icon
        return icon
    }

private var _cloudOff: ImageVector? = null

val Icons.Filled.CloudOff: ImageVector
    get() {
        _cloudOff?.let { return it }
        val icon = materialIcon(name = "Filled.CloudOff") {
            addPath(
                pathData = addPathNodes("M19.35 10.04C18.67 6.59 15.64 4 12 4c-1.48 0-2.85.43-4.01 1.17l1.46 1.46C10.21 6.23 11.08 6 12 6c3.04 0 5.5 2.46 5.5 5.5v.5H19c1.66 0 3 1.34 3 3 0 1.13-.64 2.11-1.56 2.62l1.45 1.45C23.16 18.16 24 16.68 24 15c0-2.64-2.05-4.78-4.65-4.96M3 5.27l2.75 2.74C2.56 8.15 0 10.77 0 14c0 3.31 2.69 6 6 6h11.73l2 2L21 20.73 4.27 4zM7.73 10l8 8H6c-2.21 0-4-1.79-4-4s1.79-4 4-4z"),
                fill = SolidColor(Color.Black),
                pathFillType = PathFillType.NonZero,
            )
        }
        _cloudOff = icon
        return icon
    }

private var _wifiTethering: ImageVector? = null

val Icons.Filled.WifiTethering: ImageVector
    get() {
        _wifiTethering?.let { return it }
        val icon = materialIcon(name = "Filled.WifiTethering") {
            addPath(
                pathData = addPathNodes("M12 11c-1.1 0-2 .9-2 2s.9 2 2 2 2-.9 2-2-.9-2-2-2m6 2c0-3.31-2.69-6-6-6s-6 2.69-6 6c0 2.22 1.21 4.15 3 5.19l1-1.74c-1.19-.7-2-1.97-2-3.45 0-2.21 1.79-4 4-4s4 1.79 4 4c0 1.48-.81 2.75-2 3.45l1 1.74c1.79-1.04 3-2.97 3-5.19M12 3C6.48 3 2 7.48 2 13c0 3.7 2.01 6.92 4.99 8.65l1-1.73C5.61 18.53 4 15.96 4 13c0-4.42 3.58-8 8-8s8 3.58 8 8c0 2.96-1.61 5.53-4 6.92l1 1.73c2.99-1.73 5-4.95 5-8.65 0-5.52-4.48-10-10-10"),
                fill = SolidColor(Color.Black),
                pathFillType = PathFillType.NonZero,
            )
        }
        _wifiTethering = icon
        return icon
    }

private var _sync: ImageVector? = null

val Icons.Filled.Sync: ImageVector
    get() {
        _sync?.let { return it }
        val icon = materialIcon(name = "Filled.Sync") {
            addPath(
                pathData = addPathNodes("M12 4V1L8 5l4 4V6c3.31 0 6 2.69 6 6 0 1.01-.25 1.97-.7 2.8l1.46 1.46C19.54 15.03 20 13.57 20 12c0-4.42-3.58-8-8-8m0 14c-3.31 0-6-2.69-6-6 0-1.01.25-1.97.7-2.8L5.24 7.74C4.46 8.97 4 10.43 4 12c0 4.42 3.58 8 8 8v3l4-4-4-4z"),
                fill = SolidColor(Color.Black),
                pathFillType = PathFillType.NonZero,
            )
        }
        _sync = icon
        return icon
    }

private var _phoneAndroid: ImageVector? = null

val Icons.Filled.PhoneAndroid: ImageVector
    get() {
        _phoneAndroid?.let { return it }
        val icon = materialIcon(name = "Filled.PhoneAndroid") {
            addPath(
                pathData = addPathNodes("M16 1H8C6.34 1 5 2.34 5 4v16c0 1.66 1.34 3 3 3h8c1.66 0 3-1.34 3-3V4c0-1.66-1.34-3-3-3m-2 20h-4v-1h4zm3.25-3H6.75V4h10.5z"),
                fill = SolidColor(Color.Black),
                pathFillType = PathFillType.NonZero,
            )
        }
        _phoneAndroid = icon
        return icon
    }

private var _installMobile: ImageVector? = null

val Icons.Filled.InstallMobile: ImageVector
    get() {
        _installMobile?.let { return it }
        val icon = materialIcon(name = "Filled.InstallMobile") {
            addPath(
                pathData = addPathNodes("M17 18H7V6h7V1H7c-1.1 0-2 .9-2 2v18c0 1.1.9 2 2 2h10c1.1 0 2-.9 2-2v-5h-2z"),
                fill = SolidColor(Color.Black),
                pathFillType = PathFillType.NonZero,
            )
            addPath(
                pathData = addPathNodes("m18 14 5-5-1.41-1.41L19 10.17V3h-2v7.17l-2.59-2.58L13 9z"),
                fill = SolidColor(Color.Black),
                pathFillType = PathFillType.NonZero,
            )
        }
        _installMobile = icon
        return icon
    }

private var _download: ImageVector? = null

val Icons.Filled.Download: ImageVector
    get() {
        _download?.let { return it }
        val icon = materialIcon(name = "Filled.Download") {
            addPath(
                pathData = addPathNodes("M5 20h14v-2H5zM19 9h-4V3H9v6H5l7 7z"),
                fill = SolidColor(Color.Black),
                pathFillType = PathFillType.NonZero,
            )
        }
        _download = icon
        return icon
    }

private var _wifiOff: ImageVector? = null

val Icons.Filled.WifiOff: ImageVector
    get() {
        _wifiOff?.let { return it }
        val icon = materialIcon(name = "Filled.WifiOff") {
            addPath(
                pathData = addPathNodes("M22.99 9C19.15 5.16 13.8 3.76 8.84 4.78l2.52 2.52c3.47-.17 6.99 1.05 9.63 3.7zm-4 4c-1.29-1.29-2.84-2.13-4.49-2.56l3.53 3.53zM2 3.05 5.07 6.1C3.6 6.82 2.22 7.78 1 9l1.99 2c1.24-1.24 2.67-2.16 4.2-2.77l2.24 2.24C7.81 10.89 6.27 11.73 5 13v.01L6.99 15c1.36-1.36 3.14-2.04 4.92-2.06L18.98 20l1.27-1.26L3.29 1.79zM9 17l3 3 3-3c-1.65-1.66-4.34-1.66-6 0"),
                fill = SolidColor(Color.Black),
                pathFillType = PathFillType.NonZero,
            )
        }
        _wifiOff = icon
        return icon
    }

private var _verifiedUser: ImageVector? = null

val Icons.Filled.VerifiedUser: ImageVector
    get() {
        _verifiedUser?.let { return it }
        val icon = materialIcon(name = "Filled.VerifiedUser") {
            addPath(
                pathData = addPathNodes("M12 1 3 5v6c0 5.55 3.84 10.74 9 12 5.16-1.26 9-6.45 9-12V5zm-2 16-4-4 1.41-1.41L10 14.17l6.59-6.59L18 9z"),
                fill = SolidColor(Color.Black),
                pathFillType = PathFillType.NonZero,
            )
        }
        _verifiedUser = icon
        return icon
    }

private var _syncProblem: ImageVector? = null

val Icons.Filled.SyncProblem: ImageVector
    get() {
        _syncProblem?.let { return it }
        val icon = materialIcon(name = "Filled.SyncProblem") {
            addPath(
                pathData = addPathNodes("M3 12c0 2.21.91 4.2 2.36 5.64L3 20h6v-6l-2.24 2.24C5.68 15.15 5 13.66 5 12c0-2.61 1.67-4.83 4-5.65V4.26C5.55 5.15 3 8.27 3 12m8 5h2v-2h-2zM21 4h-6v6l2.24-2.24C18.32 8.85 19 10.34 19 12c0 2.61-1.67 4.83-4 5.65v2.09c3.45-.89 6-4.01 6-7.74 0-2.21-.91-4.2-2.36-5.64zm-10 9h2V7h-2z"),
                fill = SolidColor(Color.Black),
                pathFillType = PathFillType.NonZero,
            )
        }
        _syncProblem = icon
        return icon
    }

private var _schedule: ImageVector? = null

val Icons.Filled.Schedule: ImageVector
    get() {
        _schedule?.let { return it }
        val icon = materialIcon(name = "Filled.Schedule") {
            addPath(
                pathData = addPathNodes("M11.99 2C6.47 2 2 6.48 2 12s4.47 10 9.99 10C17.52 22 22 17.52 22 12S17.52 2 11.99 2M12 20c-4.42 0-8-3.58-8-8s3.58-8 8-8 8 3.58 8 8-3.58 8-8 8"),
                fill = SolidColor(Color.Black),
                pathFillType = PathFillType.NonZero,
            )
            addPath(
                pathData = addPathNodes("M12.5 7H11v6l5.25 3.15.75-1.23-4.5-2.67z"),
                fill = SolidColor(Color.Black),
                pathFillType = PathFillType.NonZero,
            )
        }
        _schedule = icon
        return icon
    }

private var _saveAlt: ImageVector? = null

val Icons.Filled.SaveAlt: ImageVector
    get() {
        _saveAlt?.let { return it }
        val icon = materialIcon(name = "Filled.SaveAlt") {
            addPath(
                pathData = addPathNodes("M19 12v7H5v-7H3v7c0 1.1.9 2 2 2h14c1.1 0 2-.9 2-2v-7zm-6 .67 2.59-2.58L17 11.5l-5 5-5-5 1.41-1.41L11 12.67V3h2z"),
                fill = SolidColor(Color.Black),
                pathFillType = PathFillType.NonZero,
            )
        }
        _saveAlt = icon
        return icon
    }

private var _save: ImageVector? = null

val Icons.Filled.Save: ImageVector
    get() {
        _save?.let { return it }
        val icon = materialIcon(name = "Filled.Save") {
            addPath(
                pathData = addPathNodes("M17 3H5c-1.11 0-2 .9-2 2v14c0 1.1.89 2 2 2h14c1.1 0 2-.9 2-2V7zm-5 16c-1.66 0-3-1.34-3-3s1.34-3 3-3 3 1.34 3 3-1.34 3-3 3m3-10H5V5h10z"),
                fill = SolidColor(Color.Black),
                pathFillType = PathFillType.NonZero,
            )
        }
        _save = icon
        return icon
    }

private var _radar: ImageVector? = null

val Icons.Filled.Radar: ImageVector
    get() {
        _radar?.let { return it }
        val icon = materialIcon(name = "Filled.Radar") {
            addPath(
                pathData = addPathNodes("M19.74 18.33C21.15 16.6 22 14.4 22 12c0-5.52-4.48-10-10-10S2 6.48 2 12s4.48 10 10 10c2.4 0 4.6-.85 6.33-2.26.27-.22.53-.46.78-.71.03-.03.05-.06.07-.08.2-.2.39-.41.56-.62M12 20c-4.41 0-8-3.59-8-8s3.59-8 8-8 8 3.59 8 8c0 1.85-.63 3.54-1.69 4.9l-1.43-1.43c.69-.98 1.1-2.17 1.1-3.46 0-3.31-2.69-6-6-6s-6 2.69-6 6 2.69 6 6 6c1.3 0 2.51-.42 3.49-1.13l1.42 1.42C15.54 19.37 13.85 20 12 20m1.92-7.49c.17-.66.02-1.38-.49-1.9l-.02-.02c-.77-.77-2-.78-2.78-.04-.01.01-.03.02-.05.04-.78.78-.78 2.05 0 2.83l.02.02c.52.51 1.25.67 1.91.49l1.51 1.51c-.6.36-1.29.58-2.04.58-2.21 0-4-1.79-4-4s1.79-4 4-4 4 1.79 4 4c0 .73-.21 1.41-.56 2z"),
                fill = SolidColor(Color.Black),
                pathFillType = PathFillType.NonZero,
            )
        }
        _radar = icon
        return icon
    }

private var _newReleases: ImageVector? = null

val Icons.Filled.NewReleases: ImageVector
    get() {
        _newReleases?.let { return it }
        val icon = materialIcon(name = "Filled.NewReleases") {
            addPath(
                pathData = addPathNodes("m23 12-2.44-2.78.34-3.68-3.61-.82-1.89-3.18L12 3 8.6 1.54 6.71 4.72l-3.61.81.34 3.68L1 12l2.44 2.78-.34 3.69 3.61.82 1.89 3.18L12 21l3.4 1.46 1.89-3.18 3.61-.82-.34-3.68zm-10 5h-2v-2h2zm0-4h-2V7h2z"),
                fill = SolidColor(Color.Black),
                pathFillType = PathFillType.NonZero,
            )
        }
        _newReleases = icon
        return icon
    }

private var _linkOff: ImageVector? = null

val Icons.Filled.LinkOff: ImageVector
    get() {
        _linkOff?.let { return it }
        val icon = materialIcon(name = "Filled.LinkOff") {
            addPath(
                pathData = addPathNodes("M17 7h-4v1.9h4c1.71 0 3.1 1.39 3.1 3.1 0 1.43-.98 2.63-2.31 2.98l1.46 1.46C20.88 15.61 22 13.95 22 12c0-2.76-2.24-5-5-5m-1 4h-2.19l2 2H16zM2 4.27l3.11 3.11C3.29 8.12 2 9.91 2 12c0 2.76 2.24 5 5 5h4v-1.9H7c-1.71 0-3.1-1.39-3.1-3.1 0-1.59 1.21-2.9 2.76-3.07L8.73 11H8v2h2.73L13 15.27V17h1.73l4.01 4L20 19.74 3.27 3z"),
                fill = SolidColor(Color.Black),
                pathFillType = PathFillType.NonZero,
            )
        }
        _linkOff = icon
        return icon
    }

private var _folderOpen: ImageVector? = null

val Icons.Filled.FolderOpen: ImageVector
    get() {
        _folderOpen?.let { return it }
        val icon = materialIcon(name = "Filled.FolderOpen") {
            addPath(
                pathData = addPathNodes("M20 6h-8l-2-2H4c-1.1 0-1.99.9-1.99 2L2 18c0 1.1.9 2 2 2h16c1.1 0 2-.9 2-2V8c0-1.1-.9-2-2-2m0 12H4V8h16z"),
                fill = SolidColor(Color.Black),
                pathFillType = PathFillType.NonZero,
            )
        }
        _folderOpen = icon
        return icon
    }

private var _folder: ImageVector? = null

val Icons.Filled.Folder: ImageVector
    get() {
        _folder?.let { return it }
        val icon = materialIcon(name = "Filled.Folder") {
            addPath(
                pathData = addPathNodes("M10 4H4c-1.1 0-1.99.9-1.99 2L2 18c0 1.1.9 2 2 2h16c1.1 0 2-.9 2-2V8c0-1.1-.9-2-2-2h-8z"),
                fill = SolidColor(Color.Black),
                pathFillType = PathFillType.NonZero,
            )
        }
        _folder = icon
        return icon
    }

private var _downloading: ImageVector? = null

val Icons.Filled.Downloading: ImageVector
    get() {
        _downloading?.let { return it }
        val icon = materialIcon(name = "Filled.Downloading") {
            addPath(
                pathData = addPathNodes("M18.32 4.26C16.84 3.05 15.01 2.25 13 2.05v2.02c1.46.18 2.79.76 3.9 1.62zM19.93 11h2.02c-.2-2.01-1-3.84-2.21-5.32L18.31 7.1c.86 1.11 1.44 2.44 1.62 3.9m-1.62 5.9 1.43 1.43c1.21-1.48 2.01-3.32 2.21-5.32h-2.02c-.18 1.45-.76 2.78-1.62 3.89M13 19.93v2.02c2.01-.2 3.84-1 5.32-2.21l-1.43-1.43c-1.1.86-2.43 1.44-3.89 1.62M13 12V7h-2v5H7l5 5 5-5zm-2 7.93v2.02c-5.05-.5-9-4.76-9-9.95s3.95-9.45 9-9.95v2.02C7.05 4.56 4 7.92 4 12s3.05 7.44 7 7.93"),
                fill = SolidColor(Color.Black),
                pathFillType = PathFillType.NonZero,
            )
        }
        _downloading = icon
        return icon
    }

private var _dns: ImageVector? = null

val Icons.Filled.Dns: ImageVector
    get() {
        _dns?.let { return it }
        val icon = materialIcon(name = "Filled.Dns") {
            addPath(
                pathData = addPathNodes("M20 13H4c-.55 0-1 .45-1 1v6c0 .55.45 1 1 1h16c.55 0 1-.45 1-1v-6c0-.55-.45-1-1-1M7 19c-1.1 0-2-.9-2-2s.9-2 2-2 2 .9 2 2-.9 2-2 2M20 3H4c-.55 0-1 .45-1 1v6c0 .55.45 1 1 1h16c.55 0 1-.45 1-1V4c0-.55-.45-1-1-1M7 9c-1.1 0-2-.9-2-2s.9-2 2-2 2 .9 2 2-.9 2-2 2"),
                fill = SolidColor(Color.Black),
                pathFillType = PathFillType.NonZero,
            )
        }
        _dns = icon
        return icon
    }

private var _compareArrows: ImageVector? = null

val Icons.Filled.CompareArrows: ImageVector
    get() {
        _compareArrows?.let { return it }
        val icon = materialIcon(name = "Filled.CompareArrows") {
            addPath(
                pathData = addPathNodes("M9.01 14H2v2h7.01v3L13 15l-3.99-4zm5.98-1v-3H22V8h-7.01V5L11 9z"),
                fill = SolidColor(Color.Black),
                pathFillType = PathFillType.NonZero,
            )
        }
        _compareArrows = icon
        return icon
    }

private var _cloudDownload: ImageVector? = null

val Icons.Filled.CloudDownload: ImageVector
    get() {
        _cloudDownload?.let { return it }
        val icon = materialIcon(name = "Filled.CloudDownload") {
            addPath(
                pathData = addPathNodes("M19.35 10.04C18.67 6.59 15.64 4 12 4 9.11 4 6.6 5.64 5.35 8.04 2.34 8.36 0 10.91 0 14c0 3.31 2.69 6 6 6h13c2.76 0 5-2.24 5-5 0-2.64-2.05-4.78-4.65-4.96M17 13l-5 5-5-5h3V9h4v4z"),
                fill = SolidColor(Color.Black),
                pathFillType = PathFillType.NonZero,
            )
        }
        _cloudDownload = icon
        return icon
    }

private var _cloud: ImageVector? = null

val Icons.Filled.Cloud: ImageVector
    get() {
        _cloud?.let { return it }
        val icon = materialIcon(name = "Filled.Cloud") {
            addPath(
                pathData = addPathNodes("M19.35 10.04C18.67 6.59 15.64 4 12 4 9.11 4 6.6 5.64 5.35 8.04 2.34 8.36 0 10.91 0 14c0 3.31 2.69 6 6 6h13c2.76 0 5-2.24 5-5 0-2.64-2.05-4.78-4.65-4.96"),
                fill = SolidColor(Color.Black),
                pathFillType = PathFillType.NonZero,
            )
        }
        _cloud = icon
        return icon
    }

private var _android: ImageVector? = null

val Icons.Filled.Android: ImageVector
    get() {
        _android?.let { return it }
        val icon = materialIcon(name = "Filled.Android") {
            addPath(
                pathData = addPathNodes("m17.6 9.48 1.84-3.18c.16-.31.04-.69-.26-.85-.29-.15-.65-.06-.83.22l-1.88 3.24c-2.86-1.21-6.08-1.21-8.94 0L5.65 5.67c-.19-.29-.58-.38-.87-.2-.28.18-.37.54-.22.83L6.4 9.48C3.3 11.25 1.28 14.44 1 18h22c-.28-3.56-2.3-6.75-5.4-8.52M7 15.25c-.69 0-1.25-.56-1.25-1.25s.56-1.25 1.25-1.25 1.25.56 1.25 1.25-.56 1.25-1.25 1.25m10 0c-.69 0-1.25-.56-1.25-1.25s.56-1.25 1.25-1.25 1.25.56 1.25 1.25-.56 1.25-1.25 1.25"),
                fill = SolidColor(Color.Black),
                pathFillType = PathFillType.NonZero,
            )
        }
        _android = icon
        return icon
    }