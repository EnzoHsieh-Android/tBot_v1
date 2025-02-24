import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.websocket.*
import io.ktor.websocket.*
import kotlinx.serialization.json.Json
import java.time.Instant
import java.time.ZoneId

class BinanceWebSocketClient {
    private val client = HttpClient(CIO) {
        install(WebSockets)
    }

    private val json = Json { ignoreUnknownKeys = true }
    private val smcAnalyzer = SMCAnalyzer()

    suspend fun connectToMultipleTimeframes(symbol: String) {
        val timeframes = listOf("4h", "1h", "5m")
        
        try {
            client.webSocket(
                urlString = "wss://stream.binance.com:9443/ws/${symbol.lowercase()}@kline_4h/${symbol.lowercase()}@kline_1h/${symbol.lowercase()}@kline_5m"
            ) {
                println("已連接到Binance K線 WebSocket - $symbol (多時間週期)")
                try {
                    while (true) {
                        when (val frame = incoming.receive()) {
                            is Frame.Text -> {
                                val rawMessage = frame.readText()
                                val klineData = json.decodeFromString<BinanceKlineData>(rawMessage)
                                
                                // 根據數據的時間週期進行分析
                                val interval = when {
                                    rawMessage.contains("\"k\":{\"i\":\"4h\"") -> "4h"
                                    rawMessage.contains("\"k\":{\"i\":\"1h\"") -> "1h"
                                    else -> "5m"
                                }
                                
                                smcAnalyzer.analyze(klineData.kline, interval)
                            }
                            else -> println("收到非文字消息: $frame")
                        }
                    }
                } catch (e: Exception) {
                    println("接收數據時出錯: ${e.message}")
                }
            }
        } catch (e: Exception) {
            println("WebSocket連接失敗: ${e.message}")
            throw e
        }
    }

    fun close() {
        client.close()
    }
}