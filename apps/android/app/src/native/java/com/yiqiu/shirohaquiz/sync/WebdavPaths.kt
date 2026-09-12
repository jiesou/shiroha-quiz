package com.yiqiu.shirohaquiz.sync

import java.net.URLDecoder
import java.net.URLEncoder

const val SYNC_DEFAULT_DIR = "shiroha-quiz"
const val SYNC_INDEX_FILE = "index.json"
const val SYNC_PROGRESS_FILE = "progress.json"
const val SYNC_BANKS_DIR = "banks"
const val SYNC_TRASH_DIR = "trash"

data class WebdavConfig(
    val server: String = "",
    val dir: String = SYNC_DEFAULT_DIR,
    val user: String = "",
    val password: String = ""
) {
    fun normalized(): WebdavConfig = copy(
        server = server.trim(),
        dir = dir.trim().ifBlank { SYNC_DEFAULT_DIR },
        user = user.trim()
    )
}

data class RemoteBankSummary(
    val id: String,
    val name: String,
    val questionCount: Int,
    val updatedAt: String,
    val file: String
)

class WebdavException(status: Int, action: String) : Exception(describeWebdavStatus(status, action))

private fun describeWebdavStatus(status: Int, action: String): String = when (status) {
    401 -> "认证失败：请检查用户名和密码。"
    403 -> "服务器拒绝访问：该账号没有读写权限。"
    404 -> "路径不存在：请检查服务器地址和远程目录。"
    409 -> "上级目录不存在：请先在服务器上创建该路径。"
    423 -> "远程文件被锁定，请稍后重试。"
    507 -> "服务器存储空间不足。"
    else -> "${action}失败（HTTP $status）"
}

/** WebDAV 地址与文件名规则。与 Web 版 app.js 中的实现保持一致，两端必须算出同一个文件名。 */
object WebdavPaths {
    private fun encodePath(path: String): String = path.split('/').joinToString("/") { segment ->
        if (segment.isEmpty()) {
            ""
        } else {
            // 先把字面量 "+" 变成 %2B 再解码：URLDecoder 会把裸 "+" 当成空格，而 Web 端 encodeURIComponent 不会。
            val decoded = runCatching { URLDecoder.decode(segment.replace("+", "%2B"), "UTF-8") }.getOrDefault(segment)
            URLEncoder.encode(decoded, "UTF-8")
                .replace("+", "%20")
                .replace("%21", "!")
                .replace("%27", "'")
                .replace("%28", "(")
                .replace("%29", ")")
                .replace("%7E", "~")
        }
    }

    fun normalizeServer(raw: String): String {
        var text = raw.trim()
        if (text.isEmpty()) return ""
        text = text.replace(Regex("^davs://", RegexOption.IGNORE_CASE), "https://")
            .replace(Regex("^dav://", RegexOption.IGNORE_CASE), "http://")
        if (!Regex("^[a-zA-Z][a-zA-Z0-9+.-]*://").containsMatchIn(text)) text = "http://$text"
        val match = Regex("^([a-zA-Z][a-zA-Z0-9+.-]*)://([^/?#]*)").find(text) ?: return ""
        val scheme = match.groupValues[1].lowercase()
        if (scheme != "http" && scheme != "https") return ""
        val authority = match.groupValues[2]
        if (authority.isBlank()) return ""
        val path = text.substring(match.range.last + 1).substringBefore('#').substringBefore('?').trimEnd('/')
        return "$scheme://$authority" + if (path.isEmpty()) "" else "/${encodePath(path.trimStart('/'))}"
    }

    fun rootUrl(config: WebdavConfig): String {
        val base = normalizeServer(config.server)
        if (base.isEmpty()) return ""
        val dir = config.dir.trim().trim('/')
        return if (dir.isEmpty()) base else "$base/${encodePath(dir)}"
    }

    fun relUrl(config: WebdavConfig, relative: String): String {
        val root = rootUrl(config)
        if (root.isEmpty()) return ""
        val rel = relative.trim('/')
        return if (rel.isEmpty()) root else "$root/${encodePath(rel)}"
    }

    fun fileKey(id: String): String {
        val safe = id.replace(Regex("[^A-Za-z0-9_-]"), "_").take(80)
        if (safe.isNotEmpty() && safe == id) return safe
        var hash = 0
        id.forEach { hash = hash * 31 + it.code }
        return "${safe.ifEmpty { "bank" }}_${Integer.toUnsignedString(hash, 36)}"
    }
}
