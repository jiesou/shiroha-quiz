package com.yiqiu.shirohaquiz.sync

import android.content.Context
import com.yiqiu.shirohaquiz.state.QuizRepository
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

object WebdavSyncManager {
    private fun timestamp(): String {
        val format = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
        format.timeZone = TimeZone.getTimeZone("UTC")
        return format.format(Date())
    }

    private fun fileStamp(): String = timestamp().replace(Regex("[:.]"), "-")

    /** 清单里的文件路径来自远端数据，不允许它带着 `..` 逃出配置的远程目录。 */
    private fun safeRemotePath(file: String): String? {
        val trimmed = file.trim().trim('/')
        if (trimmed.isEmpty()) return null
        return trimmed.takeIf { it.split('/').none { segment -> segment.isEmpty() || segment == "." || segment == ".." } }
    }

    private fun indexBanks(index: JSONObject?): List<JSONObject> = buildList {
        val banks = index?.optJSONArray("banks") ?: return@buildList
        for (i in 0 until banks.length()) {
            val item = banks.optJSONObject(i) ?: continue
            if (item.optString("id").isNotBlank()) add(item)
        }
    }

    fun testConnection(config: WebdavConfig): String {
        val root = WebdavPaths.rootUrl(config)
        if (root.isEmpty()) throw IllegalStateException("请先填写服务器地址。")
        val response = WebdavSyncClient.request(config, "PROPFIND", root, depth = "0")
        if (response.status == 404) return "连接成功。远程目录还不存在，第一次上传时会自动创建。"
        response.requireOk("测试连接")
        return "连接成功，远程目录可访问。"
    }

    fun listRemoteBanks(config: WebdavConfig): List<RemoteBankSummary> {
        val index = WebdavSyncClient.readJson(config, SYNC_INDEX_FILE, "读取远程清单") ?: return emptyList()
        return indexBanks(index).map { item ->
            val id = item.optString("id")
            RemoteBankSummary(
                id = id,
                name = item.optString("name").ifBlank { "未命名题库" },
                questionCount = item.optInt("questionCount", 0),
                updatedAt = item.optString("updatedAt"),
                file = safeRemotePath(item.optString("file")) ?: "$SYNC_BANKS_DIR/${WebdavPaths.fileKey(id)}.json"
            )
        }
    }

    suspend fun upload(
        context: Context,
        config: WebdavConfig,
        bankIds: Set<String>,
        includeProgress: Boolean,
        onStatus: suspend (String) -> Unit,
    ): String {
        onStatus("正在准备远程目录…")
        WebdavSyncClient.ensureDirs(config)
        val selected = QuizRepository.banks.filter { bankIds.contains(it.id) }
        var trashed = 0
        val uploaded = mutableListOf<Pair<String, String>>()
        selected.forEachIndexed { index, bank ->
            onStatus("正在上传题库：${bank.name}（${index + 1}/${selected.size}）…")
            val payload = QuizRepository.exportBankForSync(bank)
            val relative = "$SYNC_BANKS_DIR/${WebdavPaths.fileKey(bank.id)}.json"
            val url = WebdavPaths.relUrl(config, relative)
            val existing = WebdavSyncClient.request(config, "GET", url)
            if (existing.status != 404) {
                // 读不到旧版本就不能盲目覆盖：备份失败同样中止，与 Web 端一致。
                existing.requireOk("读取远端题库「${bank.name}」")
                val previous = existing.jsonOrNull()
                val changed = previous == null ||
                    QuizRepository.syncBankFingerprintOfJson(previous) != QuizRepository.syncBankFingerprint(bank) ||
                    previous.optString("name") != bank.name
                if (changed) {
                    val trashUrl = WebdavPaths.relUrl(
                        config,
                        "$SYNC_TRASH_DIR/${WebdavPaths.fileKey(bank.id)}-${fileStamp()}.json"
                    )
                    WebdavSyncClient.request(config, "PUT", trashUrl, existing.body, WebdavSyncClient.jsonMedia)
                        .requireOk("备份远端旧版本")
                    trashed++
                }
            }
            WebdavSyncClient.request(config, "PUT", url, payload.toByteArray(Charsets.UTF_8), WebdavSyncClient.jsonMedia)
                .requireOk("上传题库「${bank.name}」")
            uploaded += bank.id to relative
        }
        if (includeProgress) {
            onStatus("正在合并并上传学习进度…")
            val remote = WebdavSyncClient.readJson(config, SYNC_PROGRESS_FILE, "读取远端学习进度")
            if (remote != null) QuizRepository.mergeSyncProgress(context, remote)
            WebdavSyncClient.request(
                config,
                "PUT",
                WebdavPaths.relUrl(config, SYNC_PROGRESS_FILE),
                QuizRepository.exportProgressForSync().toByteArray(Charsets.UTF_8),
                WebdavSyncClient.jsonMedia
            ).requireOk("上传学习进度")
        }
        onStatus("正在更新远程清单…")
        writeIndex(config, uploaded)
        val parts = mutableListOf("已上传 ${uploaded.size} 个题库")
        if (includeProgress) parts += "已合并学习进度"
        if (trashed > 0) parts += "已备份 $trashed 份远端旧版本"
        return parts.joinToString("；") + "。"
    }

    private fun writeIndex(config: WebdavConfig, uploaded: List<Pair<String, String>>) {
        val byId = linkedMapOf<String, JSONObject>()
        indexBanks(WebdavSyncClient.readJson(config, SYNC_INDEX_FILE, "读取远程清单")).forEach { item ->
            byId[item.optString("id")] = item
        }
        val stamp = timestamp()
        uploaded.forEach { (bankId, relative) ->
            val bank = QuizRepository.banks.firstOrNull { it.id == bankId } ?: return@forEach
            byId[bankId] = JSONObject()
                .put("id", bankId)
                .put("name", bank.name)
                .put("questionCount", bank.questions.size)
                .put("updatedAt", stamp)
                .put("file", relative)
        }
        val payload = JSONObject()
            .put("app", "Shiroha Quiz")
            .put("kind", "shiroha_quiz_webdav_index")
            .put("schemaVersion", 1)
            .put("updatedAt", stamp)
            .put("banks", JSONArray(byId.values.toList()))
        WebdavSyncClient.request(
            config,
            "PUT",
            WebdavPaths.relUrl(config, SYNC_INDEX_FILE),
            payload.toString(2).toByteArray(Charsets.UTF_8),
            WebdavSyncClient.jsonMedia
        ).requireOk("写入远程清单")
    }

    suspend fun download(
        context: Context,
        config: WebdavConfig,
        banks: List<RemoteBankSummary>,
        includeProgress: Boolean,
        onStatus: suspend (String) -> Unit,
    ): String {
        val added = mutableListOf<String>()
        val skipped = mutableListOf<String>()
        banks.forEachIndexed { index, item ->
            onStatus("正在下载题库：${item.name}（${index + 1}/${banks.size}）…")
            val response = WebdavSyncClient.request(config, "GET", WebdavPaths.relUrl(config, item.file))
            if (response.status == 404) {
                skipped += "${item.name}（远程文件缺失）"
            } else {
                response.requireOk("下载题库「${item.name}」")
                val result = QuizRepository.importSyncBank(context, response.text)
                if (result.added) added += result.bankName else skipped += "${result.bankName}（${result.reason}）"
            }
        }
        val parts = mutableListOf<String>()
        if (added.isNotEmpty()) parts += "新增 ${added.size} 个题库：${added.joinToString("、")}"
        if (skipped.isNotEmpty()) parts += "跳过 ${skipped.size} 个：${skipped.joinToString("、")}"
        if (includeProgress) {
            onStatus("正在合并远端学习进度…")
            val remote = WebdavSyncClient.readJson(config, SYNC_PROGRESS_FILE, "下载远端学习进度")
            if (remote == null) {
                parts += "远端没有学习进度"
            } else {
                val merged = QuizRepository.mergeSyncProgress(context, remote)
                parts += "已合并学习进度（错题 ${merged.wrongCount}、收藏 ${merged.favoriteCount}、记录 ${merged.recordCount}）"
            }
        }
        if (parts.isEmpty()) parts += "没有需要写入的内容"
        return parts.joinToString("；") + "。"
    }
}
