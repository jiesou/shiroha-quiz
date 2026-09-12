package com.yiqiu.shirohaquiz.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.CloudDownload
import androidx.compose.material.icons.rounded.CloudSync
import androidx.compose.material.icons.rounded.CloudUpload
import androidx.compose.material.icons.rounded.PublishedWithChanges
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.yiqiu.shirohaquiz.state.QuizRepository
import com.yiqiu.shirohaquiz.sync.RemoteBankSummary
import com.yiqiu.shirohaquiz.sync.SYNC_DEFAULT_DIR
import com.yiqiu.shirohaquiz.sync.WebdavConfig
import com.yiqiu.shirohaquiz.sync.WebdavPaths
import com.yiqiu.shirohaquiz.sync.WebdavSyncManager
import com.yiqiu.shirohaquiz.sync.WebdavSyncStore
import com.yiqiu.shirohaquiz.ui.components.ActionPillButton
import com.yiqiu.shirohaquiz.ui.components.GlassCard
import com.yiqiu.shirohaquiz.ui.components.NoticeCard
import com.yiqiu.shirohaquiz.ui.components.ShirohaHeader
import com.yiqiu.shirohaquiz.ui.theme.ShirohaSpacing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun SyncScreen(
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val saved = remember { WebdavSyncStore.load(context) }
    var server by remember { mutableStateOf(saved.server) }
    var dir by remember { mutableStateOf(saved.dir) }
    var user by remember { mutableStateOf(saved.user) }
    var password by remember { mutableStateOf(saved.password) }
    var statusText by remember { mutableStateOf("尚未连接。") }
    var statusWarning by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var uploadSelected by remember { mutableStateOf(QuizRepository.banks.map { it.id }.toSet()) }
    var uploadProgress by remember { mutableStateOf(true) }
    var remoteBanks by remember { mutableStateOf<List<RemoteBankSummary>>(emptyList()) }
    var downloadSelected by remember { mutableStateOf(emptySet<String>()) }
    var downloadProgress by remember { mutableStateOf(true) }

    fun currentConfig(): WebdavConfig =
        WebdavSyncStore.save(context, WebdavConfig(server = server, dir = dir, user = user, password = password))

    fun report(message: String, warning: Boolean) {
        statusText = message
        statusWarning = warning
    }

    fun <T> runRemote(label: String, onSuccess: (T) -> Unit, block: suspend () -> T) {
        if (busy) return
        busy = true
        report("正在${label}……", false)
        scope.launch {
            try {
                onSuccess(withContext(Dispatchers.IO) { block() })
            } catch (error: Throwable) {
                report("${label}失败：${error.message ?: "请检查配置"}", true)
            } finally {
                busy = false
            }
        }
    }

    // 进度回调来自 IO 线程，回到主线程再改状态，否则终态会被排队的进度文案覆盖。
    suspend fun reportProgress(message: String) {
        withContext(Dispatchers.Main.immediate) { report(message, false) }
    }

    Column(
        modifier = Modifier
            .verticalScroll(rememberScrollState())
            .padding(horizontal = ShirohaSpacing.Xl, vertical = ShirohaSpacing.Sm),
        verticalArrangement = Arrangement.spacedBy(ShirohaSpacing.Lg)
    ) {
        ShirohaHeader(
            kicker = "Sync",
            title = "云端同步",
            subtitle = ""
        )

        GlassCard {
            Text(
                text = "服务器配置",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                value = server,
                onValueChange = { server = it },
                label = { Text("服务器地址") },
                placeholder = { Text("https://dav.example.com/dav/backups/") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                value = dir,
                onValueChange = { dir = it },
                label = { Text("远程目录") },
                placeholder = { Text(SYNC_DEFAULT_DIR) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                value = user,
                onValueChange = { user = it },
                label = { Text("用户名") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                label = { Text("密码") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = "配置只保存在本机。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(ShirohaSpacing.Sm)) {
                ActionPillButton(
                    icon = Icons.Rounded.PublishedWithChanges,
                    text = "保存配置",
                    primary = false,
                    enabled = !busy,
                    modifier = Modifier.height(42.dp)
                ) {
                    val root = WebdavPaths.rootUrl(currentConfig())
                    if (root.isEmpty()) {
                        report("服务器地址无效。", true)
                    } else {
                        report("配置已保存。", false)
                    }
                }
                ActionPillButton(
                    icon = Icons.Rounded.CloudSync,
                    text = if (busy) "处理中" else "测试连接",
                    primary = false,
                    enabled = !busy,
                    modifier = Modifier.height(42.dp)
                ) {
                    runRemote("测试连接", { report(it, false) }) { WebdavSyncManager.testConnection(currentConfig()) }
                }
            }
            Spacer(Modifier.height(10.dp))
            NoticeCard(text = statusText, warning = statusWarning)
        }

        GlassCard {
            Text(
                text = "上传",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = "覆盖远端同名题库前，会先备份旧版本。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(10.dp))
            if (QuizRepository.banks.isEmpty()) {
                Text("本地还没有题库。", style = MaterialTheme.typography.bodyMedium)
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 240.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    QuizRepository.banks.forEach { bank ->
                        SyncCheckRow(
                            title = bank.name.ifBlank { "未命名题库" },
                            subtitle = "${bank.questions.size} 题",
                            checked = uploadSelected.contains(bank.id),
                            onCheckedChange = { checked ->
                                uploadSelected = if (checked) uploadSelected + bank.id else uploadSelected - bank.id
                            }
                        )
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            SyncCheckRow(
                title = "同时上传学习进度",
                checked = uploadProgress,
                onCheckedChange = { uploadProgress = it }
            )
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(ShirohaSpacing.Sm)) {
                ActionPillButton(
                    icon = Icons.Rounded.CloudUpload,
                    text = if (busy) "处理中" else "上传",
                    enabled = !busy,
                    modifier = Modifier.height(42.dp)
                ) {
                    val ids = uploadSelected
                    if (ids.isEmpty() && !uploadProgress) {
                        report("请至少勾选一个题库，或勾选“同时上传学习进度”。", true)
                        return@ActionPillButton
                    }
                    runRemote("上传", { report(it, false) }) {
                        WebdavSyncManager.upload(
                            context = context,
                            config = currentConfig(),
                            bankIds = ids,
                            includeProgress = uploadProgress,
                            onStatus = ::reportProgress
                        )
                    }
                }
            }
        }

        GlassCard {
            Text(
                text = "下载",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = "相同内容跳过，不同的另存为「远端副本」。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(ShirohaSpacing.Sm)) {
                ActionPillButton(
                    icon = Icons.Rounded.CloudSync,
                    text = if (busy) "处理中" else "读取远端清单",
                    primary = false,
                    enabled = !busy,
                    modifier = Modifier.height(42.dp)
                ) {
                    runRemote("读取远端清单", { banks: List<RemoteBankSummary> ->
                        remoteBanks = banks
                        if (banks.isEmpty()) {
                            downloadSelected = emptySet()
                            report("远程还没有清单，请先执行一次上传。", true)
                        } else {
                            val fresh = banks.filter { item -> QuizRepository.banks.none { it.id == item.id } }
                            downloadSelected = fresh.map { it.id }.toSet()
                            report("远程 ${banks.size} 个题库，已勾选 ${fresh.size} 个本地没有的。", false)
                        }
                    }) { WebdavSyncManager.listRemoteBanks(currentConfig()) }
                }
                ActionPillButton(
                    icon = Icons.Rounded.CloudDownload,
                    text = "全选",
                    primary = false,
                    enabled = !busy && remoteBanks.isNotEmpty(),
                    modifier = Modifier.height(42.dp)
                ) {
                    downloadSelected = remoteBanks.map { it.id }.toSet()
                }
            }
            Spacer(Modifier.height(10.dp))
            if (remoteBanks.isEmpty()) {
                Text("尚未读取远端清单，或远端没有任何题库。", style = MaterialTheme.typography.bodyMedium)
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 240.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    remoteBanks.forEach { item ->
                        val exists = QuizRepository.banks.any { it.id == item.id }
                        val stamp = item.updatedAt.take(19).replace("T", " ").ifBlank { "时间未知" }
                        SyncCheckRow(
                            title = item.name,
                            subtitle = "${item.questionCount} 题 · $stamp · ${if (exists) "本地已存在" else "本地没有"}",
                            checked = downloadSelected.contains(item.id),
                            onCheckedChange = { checked ->
                                downloadSelected = if (checked) downloadSelected + item.id else downloadSelected - item.id
                            }
                        )
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            SyncCheckRow(
                title = "同时合并远端学习进度",
                checked = downloadProgress,
                onCheckedChange = { downloadProgress = it }
            )
            Spacer(Modifier.height(10.dp))
            ActionPillButton(
                icon = Icons.Rounded.CloudDownload,
                text = if (busy) "处理中" else "下载",
                enabled = !busy,
                modifier = Modifier.height(42.dp)
            ) {
                val picked = remoteBanks.filter { downloadSelected.contains(it.id) }
                if (picked.isEmpty() && !downloadProgress) {
                    report("请至少勾选一个题库，或勾选“同时合并远端学习进度”。", true)
                    return@ActionPillButton
                }
                runRemote("下载", { message ->
                    uploadSelected = QuizRepository.banks.map { it.id }.toSet()
                    report(message, false)
                }) {
                    WebdavSyncManager.download(
                        context = context,
                        config = currentConfig(),
                        banks = picked,
                        includeProgress = downloadProgress,
                        onStatus = ::reportProgress
                    )
                }
            }
        }

        ActionPillButton(
            icon = Icons.AutoMirrored.Rounded.ArrowBack,
            text = "返回设置",
            primary = false,
            modifier = Modifier.height(42.dp),
            onClick = onBack
        )
    }
}

@Composable
private fun SyncCheckRow(
    title: String,
    subtitle: String = "",
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Checkbox(checked = checked, onCheckedChange = onCheckedChange)
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (subtitle.isNotBlank()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
