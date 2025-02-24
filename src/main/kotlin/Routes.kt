import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.websocket.*
import io.ktor.websocket.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

fun Application.configureRouting(binanceClient: BinanceWebSocketClient) {
    routing {
        get("/") {
            call.respondText("幣安WebSocket服務器正在運行")
        }

        webSocket("/ws/ticker/{symbol}") {
            val symbol = call.parameters["symbol"] ?: throw IllegalArgumentException("未指定交易對")
            
            try {
                val messageChannel = Channel<String>()
                
                // 啟動協程來處理Binance WebSocket連接
                val job = launch {
//                    binanceClient.connectToTickerStream(symbol) { message ->
//                        launch { messageChannel.send(message) }
//                    }
                }

                try {
                    // 從Channel接收消息並發送給客戶端
                    for (message in messageChannel) {
                        send(Frame.Text(message))
                    }
                } finally {
                    messageChannel.close()
                    job.cancel()
                }
            } catch (e: Exception) {
                println("WebSocket處理出錯: ${e.message}")
            }
        }
    }
} 