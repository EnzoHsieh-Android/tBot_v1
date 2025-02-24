import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName
import kotlinx.serialization.json.JsonElement

@Serializable
data class StreamData(
    @SerialName("stream") val stream: String,
    @SerialName("data") val data: JsonElement  // 使用JsonElement來處理多種可能的數據類型
)

@Serializable
data class BinanceKlineData(
    @SerialName("e") val eventType: String,
    @SerialName("E") val eventTime: Long,
    @SerialName("s") val symbol: String,
    @SerialName("k") val kline: Kline
)

@Serializable
data class Kline(
    @SerialName("t") val startTime: Long,
    @SerialName("T") val closeTime: Long,
    @SerialName("o") val openPrice: String,
    @SerialName("h") val highPrice: String,
    @SerialName("l") val lowPrice: String,
    @SerialName("c") val closePrice: String,
    @SerialName("v") val volume: String,
    @SerialName("n") val numberOfTrades: Long,
    @SerialName("x") val isClosed: Boolean
) 