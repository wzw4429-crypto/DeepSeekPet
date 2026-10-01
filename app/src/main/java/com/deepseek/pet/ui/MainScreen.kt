package com.deepseek.pet.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.time.LocalDate
import java.time.ZoneOffset
import com.deepseek.pet.data.BalanceHistory
import com.deepseek.pet.data.BalanceRepository
import com.deepseek.pet.data.BalanceState
import com.deepseek.pet.data.SecurePrefs
import com.deepseek.pet.data.UsageRange
import com.deepseek.pet.model.Mood
import com.deepseek.pet.model.PetSprite
import com.deepseek.pet.service.FloatingPetService
import com.deepseek.pet.ui.theme.HyperBlue
import com.deepseek.pet.ui.theme.HyperDivider
import com.deepseek.pet.ui.theme.HyperFill
import com.deepseek.pet.ui.theme.HyperIconBox
import com.deepseek.pet.ui.theme.HyperTextSecondary

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen() {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val repository = remember { BalanceRepository.get(context) }

    val balanceState by repository.state.collectAsStateWithLifecycle()
    val refreshing by repository.refreshing.collectAsStateWithLifecycle()

    var apiKeyInput by remember { mutableStateOf(SecurePrefs.apiKey(context)) }
    var apiKeyVisible by remember { mutableStateOf(false) }
    var maskedKey by remember { mutableStateOf(SecurePrefs.maskedApiKey(context)) }
    var thresholdInput by remember {
        mutableStateOf(
            SecurePrefs.threshold(context).let {
                if (it % 1.0 == 0.0) it.toInt().toString() else it.toString()
            }
        )
    }
    var hasOverlay by remember { mutableStateOf(Settings.canDrawOverlays(context)) }
    var petRunning by remember { mutableStateOf(FloatingPetService.isRunning) }

    val overlayLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { hasOverlay = Settings.canDrawOverlays(context) }

    val notificationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }

    // 从系统设置页返回时同步权限与运行状态
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                hasOverlay = Settings.canDrawOverlays(context)
                petRunning = FloatingPetService.isRunning
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val mood = Mood.from(balanceState, SecurePrefs.threshold(context))
    val notificationsGranted = remember { PetNotificationGrant(context).granted }

    PullToRefreshBox(
        isRefreshing = refreshing,
        onRefresh = { repository.refresh() },
        modifier = Modifier.fillMaxSize()
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
        ) {
            Spacer(Modifier.height(28.dp))
            Text(
                text = "DeepSeek娘",
                style = MaterialTheme.typography.headlineMedium
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = "桌面悬浮宠物 · 余额看板",
                style = MaterialTheme.typography.bodyMedium,
                color = HyperTextSecondary
            )
            Spacer(Modifier.height(18.dp))

            // ---------- 宠物状态 + 余额 ----------
            StatusCard(balanceState = balanceState, mood = mood, refreshing = refreshing)

            Spacer(Modifier.height(20.dp))

            // ---------- 消耗额度（由余额快照推算）----------
            HyperSectionTitle("消耗额度")
            Spacer(Modifier.height(8.dp))
            UsageCard()

            Spacer(Modifier.height(20.dp))

            // ---------- 悬浮窗 ----------
            HyperSectionTitle("悬浮宠物")
            Spacer(Modifier.height(8.dp))
            HyperCard {
                HyperListItem(
                    icon = Icons.Filled.CheckCircle,
                    tint = if (hasOverlay) HyperBlue else Color(0xFFF5423B),
                    title = "悬浮窗权限",
                    subtitle = if (hasOverlay) "已授权，可显示在桌面" else "未授权，点击前往开启",
                    onClick = {
                        if (!hasOverlay) {
                            overlayLauncher.launch(overlayPermissionIntent(context))
                        }
                    }
                )
                HyperDivider()
                HyperListItem(
                    icon = Icons.Filled.Notifications,
                    tint = HyperBlue,
                    title = "通知栏入口",
                    subtitle = if (notificationsGranted) "已开启，点击通知可回到 App" else "未开启，常驻通知可能不显示",
                    onClick = {
                        if (!notificationsGranted && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        }
                    }
                )
                HyperDivider()
                HyperListItem(
                    icon = Icons.Filled.Settings,
                    tint = HyperBlue,
                    title = "显示桌面悬浮球",
                    subtitle = if (petRunning) "正在运行，退到后台也会保持" else "未运行",
                    trailing = {
                        Switch(
                            checked = petRunning,
                            onCheckedChange = { want ->
                                if (want) {
                                    if (Settings.canDrawOverlays(context)) {
                                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                                            ContextCompat.checkSelfPermission(
                                                context,
                                                Manifest.permission.POST_NOTIFICATIONS
                                            ) != PackageManager.PERMISSION_GRANTED
                                        ) {
                                            notificationLauncher.launch(
                                                Manifest.permission.POST_NOTIFICATIONS
                                            )
                                        }
                                        FloatingPetService.start(context)
                                        petRunning = true
                                    } else {
                                        overlayLauncher.launch(overlayPermissionIntent(context))
                                    }
                                } else {
                                    FloatingPetService.stop(context)
                                    petRunning = false
                                }
                            },
                            colors = SwitchDefaults.colors(checkedTrackColor = HyperBlue)
                        )
                    }
                )
            }

            Spacer(Modifier.height(20.dp))

            // ---------- API Key ----------
            HyperSectionTitle("API Key")
            Spacer(Modifier.height(8.dp))
            HyperCard {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = if (maskedKey.isEmpty()) "尚未配置" else "当前：$maskedKey",
                        style = MaterialTheme.typography.bodyMedium,
                        color = HyperTextSecondary
                    )
                    Spacer(Modifier.height(12.dp))
                    TextField(
                        value = apiKeyInput,
                        onValueChange = { apiKeyInput = it },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        shape = RoundedCornerShape(14.dp),
                        placeholder = { Text("sk-xxxxxxxxxxxxxxxx") },
                        visualTransformation = if (apiKeyVisible) {
                            VisualTransformation.None
                        } else {
                            PasswordVisualTransformation()
                        },
                        colors = hyperFieldColors(),
                        trailingIcon = {
                            TextButton(onClick = { apiKeyVisible = !apiKeyVisible }) {
                                Text(if (apiKeyVisible) "隐藏" else "显示")
                            }
                        }
                    )
                    Spacer(Modifier.height(12.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Button(
                            onClick = {
                                SecurePrefs.saveApiKey(context, apiKeyInput)
                                maskedKey = SecurePrefs.maskedApiKey(context)
                                repository.refresh()
                            },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(50),
                            colors = ButtonDefaults.buttonColors(containerColor = HyperBlue)
                        ) {
                            Text("保存并查询")
                        }
                        Spacer(Modifier.width(10.dp))
                        TextButton(
                            onClick = {
                                apiKeyInput = ""
                                SecurePrefs.clearApiKey(context)
                                maskedKey = ""
                            }
                        ) {
                            Text("清除")
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = "Key 使用 EncryptedSharedPreferences 加密保存在本机 Keystore，" +
                            "不会上传到任何第三方服务器。",
                        style = MaterialTheme.typography.labelMedium,
                        color = HyperTextSecondary
                    )
                }
            }

            Spacer(Modifier.height(20.dp))

            // ---------- 立绘与光晕 ----------
            HyperSectionTitle("立绘与光晕")
            Spacer(Modifier.height(8.dp))
            HyperCard {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "立绘：待机 → 愤怒叉腰；点击 → 托腮笑（余额卡片展开期间保持）\n" +
                            "脚底光晕随余额变化：充足 → 绿 · 偏低 → 橙 · 告急 → 红",
                        style = MaterialTheme.typography.bodyMedium,
                        color = HyperTextSecondary,
                        lineHeight = 22.sp
                    )
                    Spacer(Modifier.height(14.dp))
                    TextField(
                        value = thresholdInput,
                        onValueChange = { thresholdInput = it.filter { c -> c.isDigit() || c == '.' } },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        shape = RoundedCornerShape(14.dp),
                        label = { Text("低余额预警阈值（元）") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        colors = hyperFieldColors()
                    )
                    Spacer(Modifier.height(12.dp))
                    Button(
                        onClick = {
                            val value = thresholdInput.toDoubleOrNull() ?: SecurePrefs.DEFAULT_THRESHOLD
                            SecurePrefs.saveThreshold(context, value)
                        },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(50),
                        colors = ButtonDefaults.buttonColors(containerColor = HyperBlue)
                    ) {
                        Text("保存阈值")
                    }
                }
            }

            Spacer(Modifier.height(20.dp))

            HyperSectionTitle("关于")
            Spacer(Modifier.height(8.dp))
            HyperCard {
                HyperListItem(
                    icon = Icons.Filled.Info,
                    tint = HyperTextSecondary,
                    title = "数据来源",
                    subtitle = "GET https://api.deepseek.com/user/balance"
                )
                HyperDivider()
                HyperListItem(
                    icon = Icons.Filled.Refresh,
                    tint = HyperTextSecondary,
                    title = "自动刷新",
                    subtitle = "每 10 分钟一次，也可下拉手动刷新"
                )
                HyperDivider()
                HyperListItem(
                    icon = Icons.Filled.Lock,
                    tint = HyperTextSecondary,
                    title = "版本",
                    subtitle = "1.0.0"
                )
            }

            Spacer(Modifier.height(40.dp))
        }
    }
}

// ----------------------------------------------------------------------
// 组件
// ----------------------------------------------------------------------

@Composable
private fun StatusCard(
    balanceState: BalanceState,
    mood: Mood,
    refreshing: Boolean
) {
    HyperCard {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            PetBubble(mood = mood, sprite = PetSprite.IDLE, height = 108.dp)
            Spacer(Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = mood.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = when (balanceState) {
                        is BalanceState.Success -> relativeTime(balanceState.updatedAt)
                        is BalanceState.Error -> "查询失败"
                        BalanceState.MissingApiKey -> "等待填写 API Key"
                        BalanceState.Loading -> if (refreshing) "刷新中…" else "查询中…"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = HyperTextSecondary
                )
            }
        }

        HyperDivider()

        when (balanceState) {
            is BalanceState.Success -> {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "总余额（${balanceState.info.currency}）",
                        style = MaterialTheme.typography.labelMedium,
                        color = HyperTextSecondary
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = "${currencySymbol(balanceState.info.currency)}${money(balanceState.info.totalBalance)}",
                        fontSize = 42.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(mood.accentColor)
                    )
                    Spacer(Modifier.height(16.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        MiniStat(
                            label = "赠金余额",
                            value = "${currencySymbol(balanceState.info.currency)}${money(balanceState.info.grantedBalance)}",
                            modifier = Modifier.weight(1f)
                        )
                        MiniStat(
                            label = "充值余额",
                            value = "${currencySymbol(balanceState.info.currency)}${money(balanceState.info.toppedUpBalance)}",
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }

            is BalanceState.Error -> {
                Text(
                    text = balanceState.message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color(0xFFF5423B),
                    modifier = Modifier.padding(16.dp)
                )
            }

            BalanceState.MissingApiKey -> {
                Text(
                    text = "还没有填写 DeepSeek API Key，填好后这里会显示余额。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = HyperTextSecondary,
                    modifier = Modifier.padding(16.dp)
                )
            }

            BalanceState.Loading -> {
                Text(
                    text = "正在查询余额…",
                    style = MaterialTheme.typography.bodyMedium,
                    color = HyperTextSecondary,
                    modifier = Modifier.padding(16.dp)
                )
            }
        }
    }
}

@Composable
private fun MiniStat(label: String, value: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(HyperFill)
            .padding(horizontal = 14.dp, vertical = 12.dp)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = HyperTextSecondary
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold
        )
    }
}

@Composable
private fun HyperSectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        color = HyperTextSecondary,
        modifier = Modifier.padding(start = 4.dp)
    )
}

/** 澎湃 OS 的卡片：纯白、20dp 圆角、无阴影，靠底色分层。 */
@Composable
private fun HyperCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.surface),
        content = content
    )
}

@Composable
private fun HyperDivider() {
    HorizontalDivider(
        color = HyperDivider,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp)
    )
}

/** 澎湃 OS 设置项：圆角方形色块图标 + 标题/副标题 + 尾部控件。 */
@Composable
private fun HyperListItem(
    icon: ImageVector,
    tint: Color,
    title: String,
    subtitle: String,
    trailing: @Composable (() -> Unit)? = null,
    onClick: (() -> Unit)? = null
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable { onClick() } else Modifier)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(HyperIconBox.size)
                .clip(HyperIconBox.shape)
                .background(tint.copy(alpha = 0.12f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = tint,
                modifier = Modifier.size(20.dp)
            )
        }
        Spacer(Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(2.dp))
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = HyperTextSecondary
            )
        }
        if (trailing != null) {
            Spacer(Modifier.width(10.dp))
            trailing()
        } else if (onClick != null) {
            Icon(
                imageVector = Icons.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = Color(0x33000000)
            )
        }
    }
}

@Composable
private fun hyperFieldColors() = TextFieldDefaults.colors(
    focusedContainerColor = HyperFill,
    unfocusedContainerColor = HyperFill,
    focusedIndicatorColor = Color.Transparent,
    unfocusedIndicatorColor = Color.Transparent
)

// ----------------------------------------------------------------------
// 权限工具
// ----------------------------------------------------------------------

private fun overlayPermissionIntent(context: Context): Intent =
    Intent(
        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
        Uri.parse("package:${context.packageName}")
    )

private class PetNotificationGrant(context: Context) {
    val granted: Boolean = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
    } else {
        true
    }
}

// ----------------------------------------------------------------------
// 消耗额度
// ----------------------------------------------------------------------

/**
 * 按区间推算消耗。
 *
 * DeepSeek 官方只开放 `get-user-balance`，**没有用量查询接口**，所以只能把每 10 分钟
 * 落一次的余额快照拿区间内的下降段累加 —— 这是估算值，不是账单。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun UsageCard() {
    val context = LocalContext.current
    val history = remember { BalanceHistory.get(context) }
    val snapshots by history.snapshots.collectAsStateWithLifecycle()

    var range by remember { mutableStateOf(UsageRange.TODAY) }
    var customFrom by remember { mutableStateOf(LocalDate.now().minusDays(6)) }
    var customTo by remember { mutableStateOf(LocalDate.now()) }
    var showFromPicker by remember { mutableStateOf(false) }
    var showToPicker by remember { mutableStateOf(false) }

    val summary = remember(snapshots, range, customFrom, customTo) {
        val (from, to) = BalanceHistory.bounds(range, customFrom, customTo)
        history.summarize(from, to)
    }

    HyperCard {
        Column(modifier = Modifier.padding(16.dp)) {
            SegmentedControl(
                options = UsageRange.values().map { it.label },
                selectedIndex = range.ordinal,
                onSelect = { range = UsageRange.values()[it] }
            )

            if (range == UsageRange.CUSTOM) {
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    DateField(
                        label = "开始",
                        date = customFrom,
                        modifier = Modifier.weight(1f),
                        onClick = { showFromPicker = true }
                    )
                    DateField(
                        label = "结束",
                        date = customTo,
                        modifier = Modifier.weight(1f),
                        onClick = { showToPicker = true }
                    )
                }
            }

            Spacer(Modifier.height(18.dp))
            Text(
                text = "¥${money(summary.amount)}",
                fontSize = 40.sp,
                fontWeight = FontWeight.Bold,
                color = HyperBlue
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = "${shortDate(summary.from)} ~ ${shortDate(summary.to)}",
                style = MaterialTheme.typography.bodyMedium,
                color = HyperTextSecondary
            )

            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                MiniStat(
                    label = "采样次数",
                    value = "${summary.samples} 次",
                    modifier = Modifier.weight(1f)
                )
                MiniStat(
                    label = "区间内充值",
                    value = if (summary.hasRecharge) "有" else "无",
                    modifier = Modifier.weight(1f)
                )
            }

            Spacer(Modifier.height(10.dp))
            Text(
                text = when {
                    summary.samples == 0 ->
                        "该区间还没有余额采样。App 每 10 分钟自动记一次，用得越久越准。"
                    summary.hasRecharge ->
                        "区间内余额有过上涨（充值），推算值可能偏小。"
                    else ->
                        "由余额快照推算（每 10 分钟采样），属估算值，与控制台账单可能有出入。"
                },
                style = MaterialTheme.typography.labelMedium,
                color = HyperTextSecondary
            )
        }
    }

    if (showFromPicker) {
        val state = rememberDatePickerState(initialSelectedDateMillis = pickerMillis(customFrom))
        DatePickerDialog(
            onDismissRequest = { showFromPicker = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let {
                        customFrom = BalanceHistory.dateFromPickerMillis(it)
                    }
                    showFromPicker = false
                }) { Text("确定") }
            },
            dismissButton = {
                TextButton(onClick = { showFromPicker = false }) { Text("取消") }
            }
        ) { DatePicker(state = state) }
    }

    if (showToPicker) {
        val state = rememberDatePickerState(initialSelectedDateMillis = pickerMillis(customTo))
        DatePickerDialog(
            onDismissRequest = { showToPicker = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let {
                        customTo = BalanceHistory.dateFromPickerMillis(it)
                    }
                    showToPicker = false
                }) { Text("确定") }
            },
            dismissButton = {
                TextButton(onClick = { showToPicker = false }) { Text("取消") }
            }
        ) { DatePicker(state = state) }
    }
}

/** 分段选择器：今日 / 近7天 / 本月 / 自选。 */
@Composable
private fun SegmentedControl(
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(50))
            .background(HyperFill)
            .padding(4.dp)
    ) {
        options.forEachIndexed { i, label ->
            val selected = i == selectedIndex
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(50))
                    .background(if (selected) HyperBlue else Color.Transparent)
                    .clickable { onSelect(i) }
                    .padding(vertical = 8.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = label,
                    fontSize = 13.sp,
                    color = if (selected) Color.White else HyperTextSecondary,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal
                )
            }
        }
    }
}

@Composable
private fun DateField(
    label: String,
    date: LocalDate,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Surface(
        modifier = modifier.clickable(onClick = onClick),
        shape = RoundedCornerShape(14.dp),
        color = HyperFill
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = HyperTextSecondary
            )
            Spacer(Modifier.height(2.dp))
            Text(text = date.toString(), style = MaterialTheme.typography.titleMedium)
        }
    }
}

private fun shortDate(millis: Long): String {
    val d = java.time.Instant.ofEpochMilli(millis)
        .atZone(java.time.ZoneId.systemDefault())
        .toLocalDate()
    return "%02d-%02d".format(d.monthValue, d.dayOfMonth)
}

/** DatePicker 用 UTC 零点表示选中日，取正午可避免跨时区算错一天。 */
private fun pickerMillis(date: LocalDate): Long =
    date.atTime(12, 0).toInstant(ZoneOffset.UTC).toEpochMilli()
