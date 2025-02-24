import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName

@Serializable
data class BinanceTickerData(
    @SerialName("e") val eventType: String,
    @SerialName("E") val eventTime: Long,
    @SerialName("s") val symbol: String,
    @SerialName("p") val priceChange: String,
    @SerialName("P") val priceChangePercent: String,
    @SerialName("c") val lastPrice: String,
    @SerialName("o") val openPrice: String,
    @SerialName("h") val highPrice: String,
    @SerialName("l") val lowPrice: String,
    @SerialName("v") val totalVolume: String,
    @SerialName("q") val totalQuoteVolume: String
) 