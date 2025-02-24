import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.plugins.websocket.*
import io.ktor.websocket.*
import kotlinx.serialization.json.Json
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.coroutineScope
import io.ktor.client.request.parameter
import io.ktor.client.request.get
import io.ktor.client.statement.*
import kotlinx.serialization.json.JsonArray

class BinanceWebSocketClient {
    private val client = HttpClient(CIO) {
        install(WebSockets)
        install(ContentNegotiation) {
           json
        }
    }

    private val json = Json { ignoreUnknownKeys = true }
    private val smcAnalyzer = SMCAnalyzer()
    
    // 添加心跳計時器和價格追蹤
    private var lastHeartbeat = System.currentTimeMillis()
    private val HEARTBEAT_INTERVAL = 60_000L // 每60秒檢查一次
    private var lastPrice = "0.0"
    private var priceChangePercent = "0.0"

    // 添加最後訊號時間追蹤
    private var lastSignalTime = System.currentTimeMillis()
    private val SIGNAL_QUIET_PERIOD = 60_000L // 60秒無訊號才顯示心跳

    // 添加 REST API 的基礎 URL
    private val BINANCE_API_URL = "https://api.binance.com/api/v3"

    init {
        // 設置交易信號回調
        smcAnalyzer.setOnTradeSignalCallback {
            lastSignalTime = System.currentTimeMillis()
        }
    }

    // 初始化時獲取歷史數據
    private suspend fun initializeHistoricalData(symbol: String) {
        val timeframes = mapOf(
            "4h" to "4h",
            "1h" to "1h",
            "5m" to "5m"
        )

        timeframes.forEach { (interval, binanceInterval) ->
            try {
                // 計算需要獲取的K線數量
                val limit = smcAnalyzer.getRequiredKlineCount(interval)
                
                // 獲取歷史K線數據
                val response = client.get("$BINANCE_API_URL/klines") {
                    parameter("symbol", symbol.uppercase())
                    parameter("interval", binanceInterval)
                    parameter("limit", limit)
                }

                // 解析響應數據
                val responseText = response.bodyAsText()
                val jsonArray = json.decodeFromString<JsonArray>(responseText)
                
                val klines = jsonArray.map { element ->
                    val item = element.toString()
                        .trim('[', ']')
                        .split(",")
                        .map { it.trim('"') }
                    
                    Kline(
                        startTime = item[0].toLong(),
                        closeTime = item[6].toLong(),
                        openPrice = item[1],
                        highPrice = item[2],
                        lowPrice = item[3],
                        closePrice = item[4],
                        volume = item[5],
                        numberOfTrades = item[8].toLong(),
                        isClosed = true
                    )
                }

                println("已獲取 $interval 歷史數據: ${klines.size} 條")
                
                // 將歷史數據添加到分析器
                klines.forEach { kline ->
                    smcAnalyzer.analyze(kline, interval)
                }
            } catch (e: Exception) {
                println("獲取 $interval 歷史數據失敗: ${e.message}")
                println("詳細錯誤信息:")
                e.printStackTrace()
            }
        }
    }

    suspend fun connectToMultipleTimeframes(symbol: String) = coroutineScope {
        try {
            // 先獲取歷史數據
            println("正在獲取歷史數據...")
            initializeHistoricalData(symbol)
            println("歷史數據初始化完成")

            // 然後建立 WebSocket 連接
            client.webSocket(
                urlString = "wss://stream.binance.com:9443/stream?streams=" +
                    "${symbol.lowercase()}@kline_4h/" +
                    "${symbol.lowercase()}@kline_1h/" +
                    "${symbol.lowercase()}@kline_5m/" +
                    "${symbol.lowercase()}@ticker"
            ) {
                println("已連接到Binance WebSocket - $symbol")
                
                // 在獨立的協程中運行心跳檢查
                val heartbeatJob = launch {
                    while (true) {
                        val currentTime = System.currentTimeMillis()
                        if (currentTime - lastSignalTime >= SIGNAL_QUIET_PERIOD) {
                            println("${formatTime(currentTime)} - ${symbol.uppercase()}: $lastPrice")
                            lastSignalTime = currentTime
                        }
                        delay(1000) // 每秒檢查一次
                    }
                }

                // 在主協程中處理WebSocket消息
                try {
                    while (true) {
                        when (val frame = incoming.receive()) {
                            is Frame.Text -> {
                                val rawMessage = frame.readText()
                                try {
                                    val streamData = json.decodeFromString<StreamData>(rawMessage)
                                    when {
                                        streamData.stream.endsWith("@ticker") -> {
                                            val tickerData = json.decodeFromJsonElement(BinanceTickerData.serializer(), streamData.data)
                                            lastPrice = tickerData.lastPrice
                                            priceChangePercent = tickerData.priceChangePercent
                                        }
                                        streamData.stream.contains("@kline_") -> {
                                            val klineData = json.decodeFromJsonElement(BinanceKlineData.serializer(), streamData.data)
                                            val interval = when {
                                                streamData.stream.contains("@kline_4h") -> "4h"
                                                streamData.stream.contains("@kline_1h") -> "1h"
                                                else -> "5m"
                                            }
                                            smcAnalyzer.analyze(klineData.kline, interval)
                                        }
                                    }
                                } catch (e: Exception) {
                                    println("解析消息出錯: ${e.message}")
                                    e.printStackTrace()
                                }
                            }
                            else -> println("收到非文字消息: $frame")
                        }
                    }
                } catch (e: Exception) {
                    println("接收數據時出錯: ${e.message}")
                    e.printStackTrace()
                    heartbeatJob.cancel()
                }
            }
        } catch (e: Exception) {
            println("連接出錯: ${e.message}")
            e.printStackTrace()
        }
    }

    private fun formatTime(timestamp: Long): String {
        return Instant.ofEpochMilli(timestamp)
            .atZone(ZoneId.systemDefault())
            .toLocalDateTime()
            .toString()
    }

    fun close() {
        client.close()
    }
}