package com.yiqiu.shirohaquiz.sync

import android.content.Context
import okhttp3.Credentials
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

object WebdavSyncStore {
    private const val PREFS_NAME = "shiroha_quiz_sync_prefs"
    private const val KEY_SERVER = "server"
    private const val KEY_DIR = "dir"
    private const val KEY_USER = "user"
    private const val KEY_PASSWORD = "password"

    fun load(context: Context): WebdavConfig {
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return WebdavConfig(
            server = prefs.getString(KEY_SERVER, "").orEmpty(),
            dir = prefs.getString(KEY_DIR, "").orEmpty(),
            user = prefs.getString(KEY_USER, "").orEmpty(),
            password = prefs.getString(KEY_PASSWORD, "").orEmpty()
        ).normalized()
    }

    fun save(context: Context, config: WebdavConfig): WebdavConfig {
        val saved = config.normalized()
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_SERVER, saved.server)
            .putString(KEY_DIR, saved.dir)
            .putString(KEY_USER, saved.user)
            .putString(KEY_PASSWORD, saved.password)
            .apply()
        return saved
    }
}

class WebdavResponse(val status: Int, val body: ByteArray) {
    val text: String get() = String(body, Charsets.UTF_8)
    fun jsonOrNull(): JSONObject? = runCatching { JSONObject(text) }.getOrNull()
    fun requireOk(action: String) { if (status !in 200..299) throw WebdavException(status, action) }
}

object WebdavSyncClient {
    internal val jsonMedia: MediaType = "application/json; charset=utf-8".toMediaType()
    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS)
        .writeTimeout(180, TimeUnit.SECONDS)
        .build()

    fun request(
        config: WebdavConfig,
        method: String,
        url: String,
        body: ByteArray? = null,
        contentType: MediaType? = null,
        depth: String? = null
    ): WebdavResponse {
        if (url.isBlank()) throw IllegalStateException("服务器地址或远程目录无效，请先保存配置。")
        val builder = Request.Builder().url(url).method(method, body?.toRequestBody(contentType))
        if (config.user.isNotBlank() || config.password.isNotBlank()) {
            builder.header("Authorization", Credentials.basic(config.user, config.password, Charsets.UTF_8))
        }
        if (depth != null) builder.header("Depth", depth)
        http.newCall(builder.build()).execute().use { response ->
            return WebdavResponse(response.code, response.body?.bytes() ?: ByteArray(0))
        }
    }

    fun ensureDir(config: WebdavConfig, url: String) {
        val response = request(config, "MKCOL", url)
        if (response.status == 405 || response.status == 301) return
        response.requireOk("创建远程目录")
    }

    fun ensureDirs(config: WebdavConfig) {
        if (WebdavPaths.rootUrl(config).isEmpty()) throw IllegalStateException("请先填写并保存服务器地址。")
        ensureDir(config, WebdavPaths.relUrl(config, ""))
        ensureDir(config, WebdavPaths.relUrl(config, SYNC_BANKS_DIR))
        ensureDir(config, WebdavPaths.relUrl(config, SYNC_TRASH_DIR))
    }

    fun readJson(config: WebdavConfig, relative: String, action: String): JSONObject? {
        val response = request(config, "GET", WebdavPaths.relUrl(config, relative))
        if (response.status == 404) return null
        response.requireOk(action)
        return response.jsonOrNull() ?: throw IllegalStateException("$action：远程文件不是合法 JSON，已中止以免覆盖它。")
    }
}
