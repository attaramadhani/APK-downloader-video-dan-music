package com.downloader.allinone.ui.screens

import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.activity.compose.BackHandler
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.downloader.allinone.model.DownloadStatus
import com.downloader.allinone.model.DownloadTask
import com.downloader.allinone.model.MediaFormat
import com.downloader.allinone.model.MediaType
import com.downloader.allinone.model.PlatformType
import com.downloader.allinone.ui.components.MediaMergerModal
import com.downloader.allinone.ui.theme.*
import com.downloader.allinone.ui.viewmodel.MainViewModel
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    viewModel: MainViewModel,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val urlInput by viewModel.urlInput.collectAsState()
    val detectedPlatform by viewModel.detectedPlatform.collectAsState()
    val isAnalyzing by viewModel.isAnalyzing.collectAsState()
    val extractedMedia by viewModel.extractedMedia.collectAsState()
    val selectedFormat by viewModel.selectedFormat.collectAsState()
    val errorMessage by viewModel.errorMessage.collectAsState()

    val activeDownloads by viewModel.activeDownloads.collectAsState()
    val historyTasks by viewModel.historyDownloads.collectAsState()

    var currentTab by rememberSaveable { mutableIntStateOf(0) }
    var showMergerModal by remember { mutableStateOf(false) }
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)

    BackHandler(enabled = drawerState.isOpen || currentTab != 0) {
        if (drawerState.isOpen) {
            scope.launch { drawerState.close() }
        } else if (currentTab != 0) {
            currentTab = 0
        }
    }

    LaunchedEffect(Unit) {
        viewModel.loadSavedHistory(context)
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet(
                drawerContainerColor = DarkSurface,
                drawerContentColor = TextPrimary,
                modifier = Modifier.width(320.dp)
            ) {
                // Header Drawer
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            Brush.linearGradient(
                                listOf(
                                    DarkSurfaceVariant,
                                    DarkSurface
                                )
                            )
                        )
                        .padding(20.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(46.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(Brush.linearGradient(listOf(IndigoPrimary, PinkAccent))),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Download,
                                contentDescription = "Logo",
                                tint = Color.White,
                                modifier = Modifier.size(26.dp)
                            )
                        }
                        Column {
                            Text(
                                text = "AnyDrop",
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.ExtraBold,
                                color = TextPrimary
                            )
                            Text(
                                text = "Universal Media Downloader",
                                style = MaterialTheme.typography.labelSmall,
                                color = TextMuted,
                                fontSize = 11.sp
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    // DNS Protection Status Badge
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color(0xFF132B1E))
                            .border(1.dp, Color(0xFF225939), RoundedCornerShape(8.dp))
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(GreenSuccess)
                        )
                        Text(
                            text = "Proteksi Enkripsi DNS (DoH) Aktif",
                            style = MaterialTheme.typography.labelSmall,
                            color = GreenSuccess,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 11.sp
                        )
                    }
                }

                HorizontalDivider(color = DarkBorder, thickness = 1.dp)

                Spacer(modifier = Modifier.height(12.dp))

                Text(
                    text = "NAVIGASI UTAMA",
                    style = MaterialTheme.typography.labelSmall,
                    color = TextMuted,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp)
                )

                // Item 1: Unduh Media (Home)
                NavigationDrawerItem(
                    icon = {
                        Icon(
                            imageVector = Icons.Default.Download,
                            contentDescription = null,
                            tint = if (currentTab == 0) CyanAccent else TextMuted
                        )
                    },
                    label = {
                        Text(
                            text = "Pengunduh Video",
                            fontWeight = if (currentTab == 0) FontWeight.Bold else FontWeight.Normal,
                            color = if (currentTab == 0) TextPrimary else TextMuted
                        )
                    },
                    badge = {
                        if (activeDownloads.isNotEmpty()) {
                            Badge(containerColor = CyanAccent, contentColor = Color.Black) {
                                Text("${activeDownloads.size}")
                            }
                        }
                    },
                    selected = currentTab == 0,
                    onClick = {
                        currentTab = 0
                        scope.launch { drawerState.close() }
                    },
                    colors = NavigationDrawerItemDefaults.colors(
                        selectedContainerColor = DarkSurfaceVariant,
                        unselectedContainerColor = Color.Transparent
                    ),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp)
                )

                // Item 2: Riwayat Unduhan
                NavigationDrawerItem(
                    icon = {
                        Icon(
                            imageVector = Icons.Default.History,
                            contentDescription = null,
                            tint = if (currentTab == 1) CyanAccent else TextMuted
                        )
                    },
                    label = {
                        Text(
                            text = "Riwayat Unduhan",
                            fontWeight = if (currentTab == 1) FontWeight.Bold else FontWeight.Normal,
                            color = if (currentTab == 1) TextPrimary else TextMuted
                        )
                    },
                    badge = {
                        if (historyTasks.isNotEmpty()) {
                            Badge(containerColor = IndigoPrimary, contentColor = Color.White) {
                                Text("${historyTasks.size}")
                            }
                        }
                    },
                    selected = currentTab == 1,
                    onClick = {
                        currentTab = 1
                        scope.launch { drawerState.close() }
                    },
                    colors = NavigationDrawerItemDefaults.colors(
                        selectedContainerColor = DarkSurfaceVariant,
                        unselectedContainerColor = Color.Transparent
                    ),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp)
                )

                Spacer(modifier = Modifier.height(10.dp))
                HorizontalDivider(color = DarkBorder, thickness = 1.dp, modifier = Modifier.padding(horizontal = 16.dp))
                Spacer(modifier = Modifier.height(10.dp))

                Text(
                    text = "FITUR & ALAT TAMBAHAN",
                    style = MaterialTheme.typography.labelSmall,
                    color = TextMuted,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp)
                )

                // Item 3: Telegram Login (VIP & Channel Privat)
                NavigationDrawerItem(
                    icon = {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.Send,
                            contentDescription = null,
                            tint = CyanAccent
                        )
                    },
                    label = {
                        Text(
                            text = "Login Telegram (VIP)",
                            color = TextPrimary,
                            fontWeight = FontWeight.Medium
                        )
                    },
                    badge = {
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = CyanAccent.copy(alpha = 0.15f)
                        ) {
                            Text(
                                text = "Channel Privat",
                                style = MaterialTheme.typography.labelSmall,
                                color = CyanAccent,
                                fontWeight = FontWeight.Bold,
                                fontSize = 10.sp,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    },
                    selected = false,
                    onClick = {
                        scope.launch { drawerState.close() }
                        viewModel.openTelegramLogin()
                    },
                    colors = NavigationDrawerItemDefaults.colors(
                        selectedContainerColor = DarkSurfaceVariant,
                        unselectedContainerColor = Color.Transparent
                    ),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp)
                )

                // Item 4: Alat Penggabung Video (Media Merger)
                NavigationDrawerItem(
                    icon = {
                        Icon(
                            imageVector = Icons.Default.Transform,
                            contentDescription = null,
                            tint = PinkAccent
                        )
                    },
                    label = {
                        Text(
                            text = "Alat Gabung Media",
                            color = TextPrimary,
                            fontWeight = FontWeight.Medium
                        )
                    },
                    badge = {
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = PinkAccent.copy(alpha = 0.15f)
                        ) {
                            Text(
                                text = "Muxing MP4",
                                style = MaterialTheme.typography.labelSmall,
                                color = PinkAccent,
                                fontWeight = FontWeight.Bold,
                                fontSize = 10.sp,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    },
                    selected = false,
                    onClick = {
                        scope.launch { drawerState.close() }
                        showMergerModal = true
                    },
                    colors = NavigationDrawerItemDefaults.colors(
                        selectedContainerColor = DarkSurfaceVariant,
                        unselectedContainerColor = Color.Transparent
                    ),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp)
                )

                Spacer(modifier = Modifier.weight(1f))

                // Footer
                HorizontalDivider(color = DarkBorder, thickness = 1.dp)
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 16.dp)
                ) {
                    Text(
                        text = "AnyDrop v1.0.0 (Release Edition)",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = "Mendukung 1000+ Situs Web • HLS Decrypter Ready",
                        style = MaterialTheme.typography.labelSmall,
                        color = TextMuted,
                        fontSize = 11.sp
                    )
                }
            }
        }
    ) {
        Scaffold(
            topBar = {
                TopAppBar(
                    navigationIcon = {
                        IconButton(onClick = { scope.launch { drawerState.open() } }) {
                            Icon(
                                imageVector = Icons.Default.Menu,
                                contentDescription = "Buka Menu",
                                tint = TextPrimary
                            )
                        }
                    },
                    title = {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(34.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(
                                        Brush.linearGradient(listOf(IndigoPrimary, PinkAccent))
                                    ),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Download,
                                    contentDescription = "Logo",
                                    tint = Color.White,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                            Column {
                                Text(
                                    text = "AnyDrop",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.ExtraBold,
                                    color = TextPrimary
                                )
                                Text(
                                    text = if (currentTab == 0) "Pengunduh Video & Audio" else "Riwayat Unduhan Tersimpan",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = CyanAccent,
                                    fontSize = 11.sp
                                )
                            }
                        }
                    },
                    actions = {
                        if (currentTab == 1 && historyTasks.isNotEmpty()) {
                            TextButton(
                                onClick = { viewModel.clearAllHistory(context) },
                                colors = ButtonDefaults.textButtonColors(contentColor = RedError)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.DeleteSweep,
                                    contentDescription = "Bersihkan Semua",
                                    modifier = Modifier.size(18.dp),
                                    tint = RedError
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = "Bersihkan",
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = RedError
                                )
                            }
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = DarkBackground
                    )
                )
            },
            containerColor = DarkBackground,
            modifier = modifier
        ) { innerPadding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
        ) {
            if (currentTab == 0) {
                // ==========================================
                // HALAMAN 1: BERANDA / UNDUH & PROSES AKTIF
                // ==========================================
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                    contentPadding = PaddingValues(top = 8.dp, bottom = 24.dp)
                ) {
                    item {
                        PlatformSelectorRow(
                            selectedPlatform = if (urlInput.isNotBlank()) detectedPlatform else PlatformType.GENERIC,
                            onPlatformClick = { }
                        )
                    }

                    item {
                        UrlInputCard(
                            url = urlInput,
                            onUrlChange = { viewModel.onUrlChanged(it) },
                            onPaste = {
                                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                val clip = clipboard.primaryClip
                                if (clip != null && clip.itemCount > 0) {
                                    val text = clip.getItemAt(0).text?.toString() ?: ""
                                    viewModel.onUrlChanged(text)
                                }
                            },
                            onClear = { viewModel.clearUrl() },
                            onAnalyze = { viewModel.analyzeUrl() },
                            isAnalyzing = isAnalyzing
                        )
                    }

                    if (errorMessage != null) {
                        item {
                            Card(
                                colors = CardDefaults.cardColors(containerColor = RedError.copy(alpha = 0.15f)),
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .border(1.dp, RedError.copy(alpha = 0.4f), RoundedCornerShape(12.dp))
                            ) {
                                Row(
                                    modifier = Modifier.padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    Icon(Icons.Default.ErrorOutline, contentDescription = null, tint = RedError)
                                    Text(
                                        text = errorMessage ?: "",
                                        color = TextPrimary,
                                        style = MaterialTheme.typography.bodySmall,
                                        modifier = Modifier.weight(1f)
                                    )
                                    IconButton(onClick = { viewModel.clearError() }, modifier = Modifier.size(24.dp)) {
                                        Icon(Icons.Default.Close, contentDescription = "Tutup", tint = TextSecondary)
                                    }
                                }
                            }
                        }
                    }

                    if (extractedMedia != null) {
                        item {
                            AnimatedVisibility(
                                visible = true,
                                enter = fadeIn() + slideInVertically()
                            ) {
                                MediaPreviewCard(
                                    media = extractedMedia!!,
                                    selectedFormat = selectedFormat,
                                    onFormatSelect = { viewModel.selectFormat(it) },
                                    onDownloadClick = { viewModel.startDownload(context) }
                                )
                            }
                        }
                    }

                    item {
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            color = Color.Transparent
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 6.dp, bottom = 2.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Download,
                                        contentDescription = null,
                                        tint = IndigoPrimary,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Text(
                                        text = "Proses Unduhan Berjalan",
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = TextPrimary
                                    )
                                    if (activeDownloads.isNotEmpty()) {
                                        Surface(
                                            shape = CircleShape,
                                            color = CyanAccent.copy(alpha = 0.2f)
                                        ) {
                                            Text(
                                                text = "${activeDownloads.size}",
                                                color = CyanAccent,
                                                fontWeight = FontWeight.Bold,
                                                style = MaterialTheme.typography.labelSmall,
                                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }

                    if (activeDownloads.isEmpty()) {
                        item {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 24.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Outlined.CloudDownload,
                                        contentDescription = null,
                                        tint = TextMuted.copy(alpha = 0.4f),
                                        modifier = Modifier.size(38.dp)
                                    )
                                    Text(
                                        text = "Tidak ada unduhan yang sedang berjalan",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = TextMuted
                                    )
                                }
                            }
                        }
                    } else {
                        items(activeDownloads, key = { it.id }) { task ->
                            ActiveDownloadCard(
                                task = task,
                                onPause = { viewModel.pauseDownload(context, task.id) },
                                onResume = { viewModel.resumeDownload(context, task) },
                                onCancel = { viewModel.removeTask(context, task.id) }
                            )
                        }
                    }
                }
            } else {
                HistoryTab(viewModel = viewModel)
            }
        }

        if (showMergerModal) {
            MediaMergerModal(onDismiss = { showMergerModal = false })
        }

        val showTelegramLoginModal by viewModel.showTelegramLoginModal.collectAsState()
        if (showTelegramLoginModal) {
            com.downloader.allinone.ui.components.TelegramLoginModal(
                onDismiss = { viewModel.closeTelegramLogin() },
                onLoginSuccess = { viewModel.onTelegramLoginSuccess() }
            )
        }
    }
}
}

@Composable
fun HistoryTab(
    viewModel: com.downloader.allinone.ui.viewmodel.MainViewModel,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val historyTasks by viewModel.historyDownloads.collectAsState()
    var selectedPlatformFilter by remember { mutableStateOf<PlatformType?>(null) }

    val filteredHistory = remember(historyTasks, selectedPlatformFilter) {
        if (selectedPlatformFilter == null) historyTasks
        else historyTasks.filter { it.platform == selectedPlatformFilter }
    }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(top = 12.dp, bottom = 24.dp)
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "Riwayat Unduhan",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary
                    )
                    Text(
                        text = "${filteredHistory.size} file tersimpan di perangkat",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary
                    )
                }

                if (filteredHistory.isNotEmpty()) {
                    TextButton(
                        onClick = { viewModel.clearAllHistory(context) },
                        colors = ButtonDefaults.textButtonColors(contentColor = RedError)
                    ) {
                        Icon(Icons.Default.DeleteSweep, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Bersihkan")
                    }
                }
            }
        }

        item {
            PlatformSelectorRow(
                selectedPlatform = selectedPlatformFilter ?: PlatformType.GENERIC,
                onPlatformClick = { clicked ->
                    selectedPlatformFilter = if (selectedPlatformFilter == clicked) null else clicked
                }
            )
        }

        if (filteredHistory.isEmpty()) {
            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 48.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.History,
                            contentDescription = null,
                            tint = TextMuted.copy(alpha = 0.4f),
                            modifier = Modifier.size(48.dp)
                        )
                        Text(
                            text = if (selectedPlatformFilter != null) "Tidak ada riwayat dari ${selectedPlatformFilter?.displayName}" else "Belum ada file di riwayat unduhan",
                            style = MaterialTheme.typography.bodyMedium,
                            color = TextMuted
                        )
                    }
                }
            }
        } else {
            items(filteredHistory, key = { it.id }) { task ->
                HistoryTaskCard(
                    task = task,
                    onDelete = { viewModel.removeTask(context, task.id) },
                    onRetry = { viewModel.retryDownload(context, task) }
                )
            }
        }
    }
}

@Composable
fun ActiveDownloadCard(
    task: DownloadTask,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onCancel: () -> Unit
) {
    val isPaused = task.status == DownloadStatus.PAUSED

    Card(
        colors = CardDefaults.cardColors(containerColor = DarkSurface),
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier
            .fillMaxWidth()
            .border(
                1.dp,
                if (isPaused) CyanAccent.copy(alpha = 0.4f) else IndigoPrimary.copy(alpha = 0.4f),
                RoundedCornerShape(14.dp)
            )
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(if (isPaused) CyanAccent.copy(alpha = 0.2f) else IndigoPrimary.copy(alpha = 0.2f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = if (isPaused) Icons.Default.Pause else Icons.Default.Downloading,
                        contentDescription = null,
                        tint = if (isPaused) CyanAccent else IndigoPrimary,
                        modifier = Modifier.size(20.dp)
                    )
                }

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = task.title,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = TextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = "${task.platform.displayName} • ${task.selectedFormat.label}",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (isPaused) CyanAccent else TextSecondary
                    )
                }

                IconButton(
                    onClick = { if (isPaused) onResume() else onPause() },
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        imageVector = if (isPaused) Icons.Default.PlayArrow else Icons.Default.Pause,
                        contentDescription = if (isPaused) "Lanjutkan" else "Jeda",
                        tint = if (isPaused) CyanAccent else PinkAccent,
                        modifier = Modifier.size(20.dp)
                    )
                }

                IconButton(onClick = onCancel, modifier = Modifier.size(28.dp)) {
                    Icon(Icons.Default.Close, contentDescription = "Batalkan", tint = TextMuted, modifier = Modifier.size(18.dp))
                }
            }

            LinearProgressIndicator(
                progress = { task.progress },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .clip(RoundedCornerShape(3.dp)),
                color = if (isPaused) CyanAccent else IndigoPrimary,
                trackColor = DarkSurfaceVariant
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                val sizeInfo = if (task.totalBytes > 0) {
                    val downloadedMb = task.downloadedBytes / (1024.0 * 1024.0)
                    val totalMb = task.totalBytes / (1024.0 * 1024.0)
                    if (totalMb >= 1024.0) {
                        String.format("%.2f / %.2f GB", downloadedMb / 1024.0, totalMb / 1024.0)
                    } else {
                        String.format("%.1f / %.1f MB", downloadedMb, totalMb)
                    }
                } else if (task.downloadedBytes > 0) {
                    val downloadedMb = task.downloadedBytes / (1024.0 * 1024.0)
                    if (downloadedMb >= 1024.0) {
                        String.format("%.2f GB", downloadedMb / 1024.0)
                    } else {
                        String.format("%.1f MB", downloadedMb)
                    }
                } else if (task.selectedFormat.approxSizeMb != null) {
                    "~${task.selectedFormat.approxSizeMb.toInt()} MB"
                } else {
                    "${(task.progress * 100).toInt()}%"
                }

                Text(
                    text = if (isPaused) "⏸ Dijeda ($sizeInfo)" else "${(task.progress * 100).toInt()}% ($sizeInfo) • ${task.speedText}",
                    style = MaterialTheme.typography.labelSmall,
                    fontSize = 11.sp,
                    color = if (isPaused) CyanAccent else TextSecondary
                )
                Text(
                    text = if (isPaused) "Tekan ▶ untuk lanjut" else task.etaText,
                    style = MaterialTheme.typography.labelSmall,
                    fontSize = 11.sp,
                    color = if (isPaused) CyanAccent else TextMuted
                )
            }
        }
    }
}

@Composable
fun HistoryTaskCard(
    task: DownloadTask,
    onDelete: () -> Unit,
    onRetry: () -> Unit = {}
) {
    val context = LocalContext.current
    val isCompleted = task.status == DownloadStatus.COMPLETED
    val isFailed = task.status == DownloadStatus.FAILED

    Card(
        colors = CardDefaults.cardColors(containerColor = DarkSurface),
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier
            .fillMaxWidth()
            .border(
                1.dp,
                if (isCompleted) GreenSuccess.copy(alpha = 0.3f) else if (isFailed) RedError.copy(alpha = 0.35f) else DarkBorder.copy(alpha = 0.5f),
                RoundedCornerShape(14.dp)
            )
            .clickable(enabled = isCompleted) {
                com.downloader.allinone.util.MediaStorageHelper.openMediaFile(
                    context = context,
                    uriString = task.outputFilePath,
                    mediaType = task.selectedFormat.type
                )
            }
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(
                        if (isFailed) RedError.copy(alpha = 0.18f)
                        else Color(task.platform.badgeColor).copy(alpha = 0.18f)
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = if (isCompleted) Icons.Default.CheckCircle else Icons.Default.ErrorOutline,
                    contentDescription = null,
                    tint = if (isCompleted) GreenSuccess else RedError,
                    modifier = Modifier.size(20.dp)
                )
            }

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = task.title,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "${task.platform.displayName} • ${task.selectedFormat.label}",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (isFailed) RedError else TextSecondary
                )
                if (isCompleted) {
                    Text(
                        text = "✓ Tersimpan di Galeri/Movies",
                        style = MaterialTheme.typography.labelSmall,
                        color = GreenSuccess,
                        fontSize = 10.sp
                    )
                } else if (isFailed) {
                    Text(
                        text = task.errorMessage ?: "Unduhan gagal",
                        style = MaterialTheme.typography.labelSmall,
                        color = RedError,
                        fontSize = 10.sp,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            if (isFailed) {
                IconButton(
                    onClick = onRetry,
                    modifier = Modifier.size(30.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = "Coba Lagi",
                        tint = CyanAccent,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }

            if (isCompleted) {
                IconButton(
                    onClick = {
                        try {
                            val uri = Uri.parse(task.outputFilePath)
                            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                                type = if (task.selectedFormat.type == MediaType.AUDIO) "audio/*" else "video/*"
                                putExtra(Intent.EXTRA_STREAM, uri)
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }
                            context.startActivity(Intent.createChooser(shareIntent, "Bagikan Media"))
                        } catch (_: Exception) {}
                    },
                    modifier = Modifier.size(30.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Share,
                        contentDescription = "Bagikan",
                        tint = TextSecondary,
                        modifier = Modifier.size(18.dp)
                    )
                }

                IconButton(
                    onClick = {
                        com.downloader.allinone.util.MediaStorageHelper.openMediaFile(
                            context = context,
                            uriString = task.outputFilePath,
                            mediaType = task.selectedFormat.type
                        )
                    },
                    modifier = Modifier.size(30.dp)
                ) {
                    Icon(
                        imageVector = if (task.selectedFormat.type == MediaType.AUDIO) Icons.Default.PlayArrow else Icons.AutoMirrored.Filled.OpenInNew,
                        contentDescription = "Buka File",
                        tint = CyanAccent,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }

            IconButton(onClick = onDelete, modifier = Modifier.size(26.dp)) {
                Icon(Icons.Default.DeleteOutline, contentDescription = "Hapus", tint = TextMuted, modifier = Modifier.size(18.dp))
            }
        }
    }
}

@Composable
fun PlatformSelectorRow(
    selectedPlatform: PlatformType,
    onPlatformClick: (PlatformType) -> Unit
) {
    val platforms = remember {
        listOf(
            PlatformType.YOUTUBE,
            PlatformType.TIKTOK,
            PlatformType.INSTAGRAM,
            PlatformType.TWITTER,
            PlatformType.SPOTIFY,
            PlatformType.SOUNDCLOUD,
            PlatformType.TELEGRAM,
            PlatformType.GENERIC
        )
    }

    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(vertical = 4.dp)
    ) {
        items(platforms) { platform ->
            val isSelected = selectedPlatform == platform
            val activeColor = Color(platform.badgeColor)

            Surface(
                shape = RoundedCornerShape(20.dp),
                color = if (isSelected) activeColor.copy(alpha = 0.22f) else DarkSurface,
                border = if (isSelected) androidx.compose.foundation.BorderStroke(1.2.dp, activeColor) else androidx.compose.foundation.BorderStroke(1.dp, DarkBorder.copy(alpha = 0.5f)),
                modifier = Modifier.clickable { onPlatformClick(platform) }
            ) {
                Text(
                    text = platform.displayName,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                    color = if (isSelected) activeColor else TextSecondary,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp)
                )
            }
        }
    }
}

@Composable
fun UrlInputCard(
    url: String,
    onUrlChange: (String) -> Unit,
    onPaste: () -> Unit,
    onClear: () -> Unit,
    onAnalyze: () -> Unit,
    isAnalyzing: Boolean
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = DarkSurface),
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, DarkBorder, RoundedCornerShape(16.dp))
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            OutlinedTextField(
                value = url,
                onValueChange = onUrlChange,
                placeholder = {
                    Text(
                        "Tempel link video / audio...",
                        color = TextMuted,
                        style = MaterialTheme.typography.bodyMedium
                    )
                },
                leadingIcon = {
                    Icon(Icons.Default.Link, contentDescription = null, tint = IndigoPrimary)
                },
                trailingIcon = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (url.isNotBlank()) {
                            IconButton(onClick = onClear, modifier = Modifier.size(28.dp)) {
                                Icon(Icons.Default.Close, contentDescription = "Hapus", tint = TextMuted)
                            }
                        }
                        IconButton(onClick = onPaste, modifier = Modifier.size(28.dp)) {
                            Icon(Icons.Default.ContentPaste, contentDescription = "Tempel", tint = CyanAccent)
                        }
                    }
                },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = IndigoPrimary,
                    unfocusedBorderColor = DarkBorder,
                    focusedTextColor = TextPrimary,
                    unfocusedTextColor = TextPrimary,
                    cursorColor = IndigoPrimary,
                    focusedContainerColor = DarkSurfaceVariant.copy(alpha = 0.5f),
                    unfocusedContainerColor = DarkSurfaceVariant.copy(alpha = 0.3f)
                ),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )

            Button(
                onClick = onAnalyze,
                enabled = url.isNotBlank() && !isAnalyzing,
                colors = ButtonDefaults.buttonColors(
                    containerColor = IndigoPrimary,
                    disabledContainerColor = IndigoPrimary.copy(alpha = 0.35f)
                ),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(46.dp)
            ) {
                if (isAnalyzing) {
                    CircularProgressIndicator(
                        color = Color.White,
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Menganalisis Link...", color = Color.White, fontWeight = FontWeight.SemiBold)
                } else {
                    Icon(Icons.Default.AutoFixHigh, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Ambil Info Media", fontWeight = FontWeight.Bold, color = Color.White)
                }
            }
        }
    }
}

@Composable
fun MediaPreviewCard(
    media: com.downloader.allinone.model.MediaInfo,
    selectedFormat: MediaFormat?,
    onFormatSelect: (MediaFormat) -> Unit,
    onDownloadClick: () -> Unit
) {
    val videoFormats = remember(media.formats) {
        media.formats.filter { it.type == MediaType.VIDEO }
    }
    val audioFormats = remember(media.formats) {
        media.formats.filter { it.type == MediaType.AUDIO }
    }

    var selectedTab by remember(media.url) {
        mutableStateOf(if (videoFormats.isNotEmpty()) MediaType.VIDEO else MediaType.AUDIO)
    }

    val activeFormats = if (selectedTab == MediaType.VIDEO) videoFormats else audioFormats

    Card(
        colors = CardDefaults.cardColors(containerColor = DarkSurface),
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, IndigoPrimary.copy(alpha = 0.4f), RoundedCornerShape(16.dp))
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(width = 100.dp, height = 70.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(DarkSurfaceVariant),
                    contentAlignment = Alignment.Center
                ) {
                    if (media.thumbnailUrl.isNotBlank()) {
                        AsyncImage(
                            model = media.thumbnailUrl,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )
                    } else {
                        Icon(
                            imageVector = if (media.platform == PlatformType.SPOTIFY || media.platform == PlatformType.SOUNDCLOUD)
                                Icons.Default.MusicNote else Icons.Default.PlayCircle,
                            contentDescription = null,
                            tint = Color(media.platform.badgeColor),
                            modifier = Modifier.size(32.dp)
                        )
                    }
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(3.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(Color.Black.copy(alpha = 0.75f))
                            .padding(horizontal = 4.dp, vertical = 1.dp)
                    ) {
                        Text(
                            text = media.durationText,
                            style = MaterialTheme.typography.labelSmall,
                            fontSize = 9.sp,
                            color = Color.White
                        )
                    }
                }

                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(3.dp)
                ) {
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = Color(media.platform.badgeColor).copy(alpha = 0.2f)
                    ) {
                        Text(
                            text = media.platform.displayName,
                            style = MaterialTheme.typography.labelSmall,
                            color = Color(media.platform.badgeColor),
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                    Text(
                        text = media.title,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = TextPrimary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = media.author,
                        style = MaterialTheme.typography.labelSmall,
                        color = TextSecondary
                    )
                }
            }

            HorizontalDivider(color = DarkBorder.copy(alpha = 0.6f))

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(DarkSurfaceVariant)
                    .padding(3.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                if (videoFormats.isNotEmpty()) {
                    val isVideoActive = selectedTab == MediaType.VIDEO
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = if (isVideoActive) IndigoPrimary else Color.Transparent,
                        modifier = Modifier
                            .weight(1f)
                            .clickable {
                                selectedTab = MediaType.VIDEO
                                videoFormats.firstOrNull()?.let { onFormatSelect(it) }
                            }
                    ) {
                        Row(
                            modifier = Modifier.padding(vertical = 8.dp),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.Movie, contentDescription = null, tint = if (isVideoActive) Color.White else TextSecondary, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "Video MP4 (${videoFormats.size})",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                                color = if (isVideoActive) Color.White else TextSecondary
                            )
                        }
                    }
                }

                if (audioFormats.isNotEmpty()) {
                    val isAudioActive = selectedTab == MediaType.AUDIO
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = if (isAudioActive) IndigoPrimary else Color.Transparent,
                        modifier = Modifier
                            .weight(1f)
                            .clickable {
                                selectedTab = MediaType.AUDIO
                                audioFormats.firstOrNull()?.let { onFormatSelect(it) }
                            }
                    ) {
                        Row(
                            modifier = Modifier.padding(vertical = 8.dp),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.Audiotrack, contentDescription = null, tint = if (isAudioActive) Color.White else TextSecondary, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "Audio Musik (${audioFormats.size})",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                                color = if (isAudioActive) Color.White else TextSecondary
                            )
                        }
                    }
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                activeFormats.forEach { format ->
                    val isChosen = selectedFormat?.id == format.id
                    val animatedBg by animateColorAsState(
                        targetValue = if (isChosen) IndigoPrimary.copy(alpha = 0.18f) else DarkSurfaceVariant.copy(alpha = 0.6f),
                        animationSpec = tween(200),
                        label = "formatBg"
                    )

                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = animatedBg,
                        border = if (isChosen) androidx.compose.foundation.BorderStroke(1.5.dp, IndigoPrimary) else androidx.compose.foundation.BorderStroke(1.dp, DarkBorder.copy(alpha = 0.4f)),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onFormatSelect(format) }
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                                modifier = Modifier.weight(1f)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(28.dp)
                                        .clip(CircleShape)
                                        .background(if (isChosen) IndigoPrimary else DarkBorder.copy(alpha = 0.5f)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = if (isChosen) Icons.Default.Check else if (format.type == MediaType.AUDIO) Icons.Default.MusicNote else Icons.Default.PlayArrow,
                                        contentDescription = null,
                                        tint = if (isChosen) Color.White else TextMuted,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }

                                Column {
                                    Text(
                                        text = format.label,
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = if (isChosen) FontWeight.Bold else FontWeight.Medium,
                                        color = if (isChosen) TextPrimary else TextSecondary
                                    )
                                    if (format.note.isNotBlank()) {
                                        Text(
                                            text = format.note,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = if (isChosen) CyanAccent else TextMuted,
                                            fontSize = 11.sp
                                        )
                                    }
                                }
                            }

                            Surface(
                                shape = RoundedCornerShape(6.dp),
                                color = DarkBackground.copy(alpha = 0.8f)
                            ) {
                                val labelText = if (format.approxSizeMb != null) {
                                    if (format.approxSizeMb >= 1000) {
                                        String.format("%.1f GB", format.approxSizeMb / 1024.0)
                                    } else {
                                        "~${format.approxSizeMb.toInt()} MB"
                                    }
                                } else {
                                    "Durasi Penuh"
                                }
                                Text(
                                    text = labelText,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (format.approxSizeMb != null) CyanAccent else GreenSuccess,
                                    fontWeight = FontWeight.SemiBold,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp)
                                )
                            }
                        }
                    }
                }
            }

            Button(
                onClick = onDownloadClick,
                colors = ButtonDefaults.buttonColors(
                    containerColor = GreenSuccess
                ),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
            ) {
                Icon(Icons.Default.Download, contentDescription = null, tint = Color.White, modifier = Modifier.size(20.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Download Sekarang (${selectedFormat?.resolution ?: selectedFormat?.extension?.uppercase() ?: "MP4"})",
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp,
                    color = Color.White
                )
            }
        }
    }
}
