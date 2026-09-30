package com.guideglasses.companion

import android.os.SystemClock
import android.util.Log
import java.io.IOException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicInteger

/**
 * 在手機上開一個極小的 HTTP 伺服器，讓眼鏡直接從熱點區網拿定位。
 *
 * ### 為什麼
 *
 * 以前座標要繞一圈：手機 → 4G → 後端 → 4G → 眼鏡。眼鏡本來就連著手機的熱點
 * （眼鏡沒有 SIM 卡），直接問手機只隔一層區網：延遲從數秒降到毫秒級，
 * 而且 4G 或後端斷掉時步行導航還能繼續拿到位置。
 *
 * ### 為什麼不用現成的 HTTP 伺服器函式庫
 *
 * 只有一個 GET 端點、每秒一次，自己讀一行請求、回一個 JSON 就夠了；
 * 內容由 [LocationHttpResponder] 決定（可在 JVM 上測試）。
 *
 * 綁在所有網路介面上：當熱點時手機的熱點 IP 每次可能不同（Android 11 起會隨機化），
 * 綁 0.0.0.0 最單純；同熱點的其他裝置也連得到，所以要金鑰（見 [LocationHttpResponder]）。
 */
internal class LocalLocationServer(
    private val port: Int,
    private val apiKey: String,
    private val latestFix: () -> LocationHttpResponder.Fix?,
) {

    /** 成功回給眼鏡幾次座標，顯示在畫面上讓人知道直連有沒有在運作。 */
    val servedCount = AtomicInteger(0)

    @Volatile
    private var server: ServerSocket? = null

    /** @return 埠被佔用等原因開不起來時回 false（眼鏡仍可經由後端取得位置）。 */
    fun start(): Boolean {
        if (server != null) return true
        return try {
            val socket = ServerSocket().apply {
                reuseAddress = true
                bind(InetSocketAddress(port))
            }
            server = socket
            Thread({ acceptLoop(socket) }, "glasses-direct-$port").apply { isDaemon = true }.start()
            Log.i(TAG, "眼鏡直連伺服器已啟動，port $port")
            true
        } catch (e: IOException) {
            Log.e(TAG, "眼鏡直連伺服器啟動失敗（port $port）", e)
            false
        }
    }

    fun stop() {
        runCatching { server?.close() }
        server = null
    }

    private fun acceptLoop(socket: ServerSocket) {
        while (!socket.isClosed) {
            val client = try {
                socket.accept()
            } catch (e: IOException) {
                break // stop() 關掉 socket 時會走到這裡
            }
            runCatching { handle(client) }.onFailure { Log.w(TAG, "處理眼鏡請求失敗", it) }
        }
    }

    private fun handle(client: Socket): Unit = client.use { connection ->
        // 眼鏡每秒問一次，一個連線不該卡住下一個。
        connection.soTimeout = READ_TIMEOUT_MS
        val reader = connection.getInputStream().bufferedReader(Charsets.UTF_8)

        val requestLine = reader.readLine() ?: return
        var apiKeyHeader: String? = null
        while (true) {
            val line = reader.readLine() ?: break
            if (line.isEmpty()) break
            val name = line.substringBefore(':').trim()
            if (name.equals("X-Api-Key", ignoreCase = true)) apiKeyHeader = line.substringAfter(':').trim()
        }

        val parts = requestLine.split(' ')
        val reply = LocationHttpResponder.respond(
            method = parts.getOrElse(0) { "" },
            path = parts.getOrElse(1) { "" },
            apiKeyHeader = apiKeyHeader,
            expectedKey = apiKey,
            fix = latestFix(),
            nowElapsedNanos = SystemClock.elapsedRealtimeNanos(),
        )
        if (reply.status == 200 && requestLine.contains("/current-location")) servedCount.incrementAndGet()

        val body = reply.body.toByteArray(Charsets.UTF_8)
        val head = "HTTP/1.1 ${reply.status} ${reason(reply.status)}\r\n" +
            "Content-Type: application/json; charset=utf-8\r\n" +
            "Content-Length: ${body.size}\r\n" +
            "Connection: close\r\n\r\n"
        val output = connection.getOutputStream()
        output.write(head.toByteArray(Charsets.US_ASCII))
        output.write(body)
        output.flush()
    }

    private fun reason(status: Int) = when (status) {
        200 -> "OK"
        401 -> "Unauthorized"
        404 -> "Not Found"
        405 -> "Method Not Allowed"
        else -> "Error"
    }

    private companion object {
        const val TAG = "GlassesDirect"
        const val READ_TIMEOUT_MS = 2_000
    }
}
