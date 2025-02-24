import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName

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