package io.github.pratae.lyricsync

import android.content.Context
import android.content.SharedPreferences
import android.media.MediaMetadata
import android.util.Log
import com.hchen.superlyricapi.ISuperLyric
import com.hchen.superlyricapi.SuperLyricData
import com.hchen.superlyricapi.SuperLyricTool
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.jvm.Volatile
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

object SyncManager {

    private const val PREFS_NAME = "sync_manager_prefs"
    private const val KEY_PC_IP = "pc_ip"

    // ===== 状态：曲目 =====
    private val _currentTrack = MutableStateFlow("No Track")
    val currentTrack: StateFlow<String> = _currentTrack.asStateFlow()

    private val _currentArtist = MutableStateFlow("Unknown Artist")
    val currentArtist: StateFlow<String> = _currentArtist.asStateFlow()

    // ===== 状态：歌词 =====
    private val _currentLyric = MutableStateFlow("No lyric yet")
    val currentLyric: StateFlow<String> = _currentLyric.asStateFlow()
    @Volatile
    private var lastLyricTranslation: String = ""
    @Volatile
    private var lastLyricDelay: Long = 0L

    // ===== 状态：连接 =====
    private val _connectionStatus = MutableStateFlow("Disconnected")
    val connectionStatus: StateFlow<String> = _connectionStatus.asStateFlow()

    private val _pcIpAddress = MutableStateFlow("")
    val pcIpAddress: StateFlow<String> = _pcIpAddress.asStateFlow()

    // ===== 网络 & 协程 =====
    private var pcIp: String = ""
    private val client = OkHttpClient()
    private val scope = CoroutineScope(Dispatchers.IO)

    // ===== SuperLyric =====
    private lateinit var appContext: Context
    private var superLyricListener: ISuperLyric? = null
    private lateinit var prefs: SharedPreferences

    /**
     * 在 Application / MainActivity.onCreate 里调用一次
     */
    fun init(context: Context) {
        if (this::appContext.isInitialized && superLyricListener != null) return
        appContext = context.applicationContext
        prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val savedIp = prefs.getString(KEY_PC_IP, "") ?: ""
        pcIp = savedIp
        _pcIpAddress.value = savedIp
        if (savedIp.isNotBlank()) {
            _connectionStatus.value = "Target: $savedIp"
            syncCurrentStateToPc()
        }
        registerSuperLyric()
    }

    private fun registerSuperLyric() {
        val listener = object : ISuperLyric.Stub() {

            override fun onSuperLyric(data: SuperLyricData) {
                // ===== 1. 先处理歌词 =====
                val lyric = data.lyric ?: return
                val translation = data.translation ?: ""
                val delay = data.delay?.toLong() ?: 0L

                Log.d(
                    "SyncManager",
                    "SuperLyric line: $lyric | translation: $translation | delay: $delay"
                )

                _currentLyric.value = lyric
                lastLyricTranslation = translation
                lastLyricDelay = delay
                sendLyricToPc(lyric, translation, delay)

                // ===== 2. 再尝试从 MediaMetadata 里拿歌名 / 艺人 =====
                // SuperLyricData 里带的就是 android.media.MediaMetadata
                data.mediaMetadata?.let { metadata ->
                    val title = metadata.getString(MediaMetadata.METADATA_KEY_TITLE) ?: ""
                    val artist =
                        metadata.getString(MediaMetadata.METADATA_KEY_ARTIST)
                            ?: metadata.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
                            ?: ""

                    if (title.isNotBlank() || artist.isNotBlank()) {
                        val finalArtist =
                            if (artist.isNotBlank()) artist else "Unknown Artist"

                        // 这里直接复用你原来的逻辑
                        updateTrackInfo(title, finalArtist)
                    }
                }
            }

            override fun onStop(data: SuperLyricData) {
                Log.d("SyncManager", "SuperLyric stopped from ${data.packageName}")
                _currentLyric.value = "Playback stopped"
                lastLyricTranslation = ""
                lastLyricDelay = 0L
                sendLyricStopToPc()
            }
        }

        superLyricListener = listener
        SuperLyricTool.registerSuperLyric(appContext, listener)
    }

    // 通知监听服务用来更新曲目（仍然保留，做兜底用）
    fun updateTrackInfo(title: String, artist: String) {
        if (_currentTrack.value != title || _currentArtist.value != artist) {
            _currentTrack.value = title
            _currentArtist.value = artist
            sendTrackToPc(title, artist)
        }
    }

    fun setPcIp(ip: String) {
        val sanitizedIp = ip.trim()
        pcIp = sanitizedIp
        _pcIpAddress.value = sanitizedIp
        if (this::prefs.isInitialized) {
            prefs.edit().putString(KEY_PC_IP, sanitizedIp).apply()
        }
        _connectionStatus.value = "Target: $sanitizedIp"
        syncCurrentStateToPc()
    }

    // ================== 发送到 PC ==================

    private fun sendTrackToPc(title: String, artist: String) {
        if (pcIp.isEmpty()) return

        scope.launch {
            try {
                val json =
                    """{"type":"track","title":${title.toJsonString()},"artist":${artist.toJsonString()}}"""
                val body =
                    json.toRequestBody("application/json; charset=utf-8".toMediaType())
                val request = Request.Builder()
                    .url("http://$pcIp:8080/sync")
                    .post(body)
                    .build()

                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        Log.e("SyncManager", "Failed to sync track: ${response.code}")
                        _connectionStatus.value = "Track error: ${response.code}"
                    } else {
                        _connectionStatus.value = "Synced track: $title"
                    }
                }
            } catch (e: Exception) {
                Log.e("SyncManager", "Error sending track to PC", e)
                _connectionStatus.value = "Track error: ${e.message}"
            }
        }
    }

    private fun sendLyricToPc(lyric: String, translation: String, delay: Long) {
        if (pcIp.isEmpty()) return

        scope.launch {
            try {
                val json = """
                    {
                        "type":"lyric",
                        "lyric":${lyric.toJsonString()},
                        "translation":${translation.toJsonString()},
                        "delay":$delay
                    }
                """.trimIndent()
                val body =
                    json.toRequestBody("application/json; charset=utf-8".toMediaType())
                val request = Request.Builder()
                    .url("http://$pcIp:8080/lyric")
                    .post(body)
                    .build()

                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        Log.e("SyncManager", "Failed to sync lyric: ${response.code}")
                        _connectionStatus.value = "Lyric error: ${response.code}"
                    } else {
                        _connectionStatus.value = "Lyric synced"
                    }
                }
            } catch (e: Exception) {
                Log.e("SyncManager", "Error sending lyric to PC", e)
                _connectionStatus.value = "Lyric error: ${e.message}"
            }
        }
    }

    private fun sendLyricStopToPc() {
        if (pcIp.isEmpty()) return

        scope.launch {
            try {
                val json = """{"type":"stop"}"""
                val body =
                    json.toRequestBody("application/json; charset=utf-8".toMediaType())
                val request = Request.Builder()
                    .url("http://$pcIp:8080/lyric")
                    .post(body)
                    .build()

                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        Log.e("SyncManager", "Failed to send stop: ${response.code}")
                    }
                }
            } catch (e: Exception) {
                Log.e("SyncManager", "Error sending stop to PC", e)
            }
        }
    }

    private fun syncCurrentStateToPc() {
        if (pcIp.isEmpty()) return
        sendTrackToPc(_currentTrack.value, _currentArtist.value)
        sendLyricToPc(_currentLyric.value, lastLyricTranslation, lastLyricDelay)
    }

    // ===== 小工具：安全转成 JSON 字符串 =====
    private fun String.toJsonString(): String =
        "\"" + this.replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n") + "\""
}
