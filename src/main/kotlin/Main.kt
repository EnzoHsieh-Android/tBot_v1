import kotlinx.coroutines.runBlocking

fun main() {
    val binanceClient = BinanceWebSocketClient()

    runBlocking {
        binanceClient.connectToMultipleTimeframes("btcusdt")
    }
}