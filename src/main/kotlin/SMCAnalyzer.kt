import java.time.Instant
import java.time.ZoneId
import kotlin.math.abs

class SMCAnalyzer {
    // 分別保存不同時間週期的K線數據
    private val klineBuffers = mapOf(
        "4h" to mutableListOf(),  // 4小時判斷主趨勢
        "1h" to mutableListOf(),  // 1小時判斷中期趨勢
        "5m" to mutableListOf<Kline>()   // 5分鐘找進場點
    )
    
    private val bufferSizes = mapOf(
        "4h" to 20,
        "1h" to 24,
        "5m" to 30
    )

    // 為不同時間週期設置閾值 - 優化比特幣市場
    private val thresholds = mapOf(
        "4h" to PriceThresholds(
            minPriceChangePercent = 1.2,    // 降低到1.2%，適應BTC波動特性
            minVolumeMultiplier = 1.6,      // 降低到1.6倍
            minSwingPoints = 3              // 保持不變
        ),
        "1h" to PriceThresholds(
            minPriceChangePercent = 0.7,    // 降低到0.7%
            minVolumeMultiplier = 1.4,      // 降低到1.4倍
            minSwingPoints = 2              // 保持不變
        ),
        "5m" to PriceThresholds(
            minPriceChangePercent = 0.4,    // 提高到0.4%，減少噪音
            minVolumeMultiplier = 1.5,      // 提高到1.5倍
            minSwingPoints = 2              // 保持不變
        )
    )

    // 新增訂單塊和流動性區域的閾值設置 - 優化比特幣市場
    private val orderBlockThresholds = mapOf(
        "4h" to OrderBlockThresholds(
            minVolumeMagnitude = 2.0,     // 降低到2.0倍
            maxCandleCount = 5,           // 保持不變
            priceRejectionPercent = 1.0   // 降低到1.0%
        ),
        "1h" to OrderBlockThresholds(
            minVolumeMagnitude = 1.8,     // 降低到1.8倍
            maxCandleCount = 8,           // 保持不變
            priceRejectionPercent = 0.7   // 降低到0.7%
        ),
        "5m" to OrderBlockThresholds(
            minVolumeMagnitude = 1.6,     // 降低到1.6倍
            maxCandleCount = 12,          // 保持不變
            priceRejectionPercent = 0.4   // 降低到0.4%
        )
    )
    
    // 新增FVG閾值設置 - 優化比特幣市場
    private val fvgThresholds = mapOf(
        "4h" to 0.5,   // 4小時圖需要至少0.5%的缺口
        "1h" to 0.3,   // 1小時圖需要至少0.3%的缺口
        "5m" to 0.15   // 5分鐘圖需要至少0.15%的缺口
    )

    private data class PriceThresholds(
        val minPriceChangePercent: Double,  // 最小價格變動百分比
        val minVolumeMultiplier: Double,    // 最小交易量倍數
        val minSwingPoints: Int             // 最小擺動點數量
    )

    private data class OrderBlockThresholds(
        val minVolumeMagnitude: Double,    // 訂單塊的最小交易量倍數
        val maxCandleCount: Int,           // 尋找訂單塊的範圍
        val priceRejectionPercent: Double  // 價格拒絕區域的百分比
    )

    private data class OrderBlock(
        val startPrice: Double,
        val endPrice: Double,
        val volume: Double,
        val type: OrderBlockType,          // BULLISH或BEARISH
        val strength: Double,              // 訂單塊強度評分
        val creationTime: Long = 0         // 創建時間
    )

    private enum class OrderBlockType {
        BULLISH, BEARISH
    }

    private data class RiskManagement(
        val entryPrice: Double,
        val stopLoss: Double,
        val targets: List<Target>,
        val riskRewardRatio: Double
    )

    private data class Target(
        val price: Double,
        val percentage: Int  // 倉位比例
    )

    // 添加回調函數類型
    private var onTradeSignal: (() -> Unit)? = null

    // 設置回調函數的方法
    fun setOnTradeSignalCallback(callback: () -> Unit) {
        onTradeSignal = callback
    }

    fun analyze(kline: Kline, interval: String) {
        // 添加調試信息
        //println("收到${interval}K線數據: 開盤:${kline.openPrice} 收盤:${kline.closePrice} 是否收盤:${kline.isClosed}")
        
        if (kline.isClosed) {
            val buffer = klineBuffers[interval] ?: return
            buffer.add(kline)
            
            // 維護固定大小的緩衝區，移除最舊的數據
            if (buffer.size > (bufferSizes[interval] ?: 20)) {
                buffer.removeAt(0)
            }

            // 輸出當前緩衝區大小
            println("${interval}緩衝區大小: ${buffer.size}/${bufferSizes[interval]}")

            // 確保所有時間週期都有足夠的數據進行分析
            if (klineBuffers.all { it.value.size >= 3 }) {
                println("所有時間週期數據充足，開始分析市場結構")
                analyzeMarketStructure()
            } else {
                // 輸出各時間週期的數據收集情況
                klineBuffers.forEach { (interval, buffer) ->
                    println("${interval}數據: ${buffer.size}/3")
                }
            }
        }
    }

    private fun analyzeMarketStructure() {
        try {
            // 獲取不同時間週期的K線數據
            val mainTimeframe = klineBuffers["4h"]!!
            val intermediateTimeframe = klineBuffers["1h"]!!
            val shortTimeframe = klineBuffers["5m"]!!

            // 第一步：分析主要時間週期（4小時圖）
            println("分析4小時圖主趨勢...")
            val mainOrderBlocks = findOrderBlocks(mainTimeframe, "4h")
            val mainFVGs = findFairValueGaps(mainTimeframe, "4h")
            val mainTrend = analyzeTrend(mainTimeframe, "4h")

            // 輸出主要分析結果
            println("4小時圖分析結果:")
            println("- 趨勢: $mainTrend")
            println("- 訂單塊數量: ${mainOrderBlocks.size}")
            println("- FVG數量: ${mainFVGs.size}")

            // 只有在主趨勢明確（非中性）時繼續分析
            if (mainTrend != Trend.NEUTRAL) {
                // 第二步：分析中期時間週期（1小時圖）
                val intermediateTrend = analyzeTrend(intermediateTimeframe, "1h")
                
                // 只有當主趨勢和中期趨勢一致時繼續分析
                if (mainTrend == intermediateTrend) {
                    // 1. 尋找中期時間週期的訂單塊
                    val intermediateOrderBlocks = findOrderBlocks(intermediateTimeframe, "1h")
                    // 2. 尋找中期時間週期的公允價值缺口
                    val intermediateFVGs = findFairValueGaps(intermediateTimeframe, "1h")
                    
                    // 第三步：在短期時間週期（5分鐘圖）尋找具體進場機會
                    analyzeEntryWithOrderBlocks(
                        shortTimeframe,
                        mainTrend,
                        mainOrderBlocks,
                        intermediateOrderBlocks,
                        mainFVGs,
                        intermediateFVGs
                    )
                }
            } else {
                println("主趨勢不明確，暫不產生交易信號")
            }
        } catch (e: Exception) {
            println("分析市場結構時出錯: ${e.message}")
            e.printStackTrace()
        }
    }

    private fun analyzeTrend(klines: List<Kline>, interval: String): Trend {
        val current = klines.last()
        val previous = klines[klines.size - 2]
        val prePrevious = klines[klines.size - 3]

        // 獲取當前時間週期的閾值
        val threshold = thresholds[interval] ?: thresholds["1h"]!!

        // 轉換價格為Double以便計算
        val currentHigh = current.highPrice.toDouble()
        val currentLow = current.lowPrice.toDouble()
        val previousHigh = previous.highPrice.toDouble()
        val previousLow = previous.lowPrice.toDouble()
        val prePreviousHigh = prePrevious.highPrice.toDouble()
        val prePreviousLow = prePrevious.lowPrice.toDouble()
        
        // 計算高點和低點的變動幅度
        val hhChange1 = ((currentHigh - previousHigh) / previousHigh * 100)
        val hhChange2 = ((previousHigh - prePreviousHigh) / prePreviousHigh * 100)
        val llChange1 = ((previousLow - currentLow) / previousLow * 100)
        val llChange2 = ((prePreviousLow - previousLow) / prePreviousLow * 100)

        // 計算交易量條件
        val currentVolume = current.volume.toDouble()
        val avgVolume = klines.takeLast(5)
            .map { it.volume.toDouble() }
            .average()
        val volumeMultiplier = currentVolume / avgVolume

        // 檢查交易量是否符合要求
        val hasValidVolume = volumeMultiplier >= threshold.minVolumeMultiplier

        return when {
            // 上升趨勢確認
            currentHigh > previousHigh && previousHigh > prePreviousHigh &&
            currentLow > previousLow && previousLow > prePreviousLow &&
            hhChange1 >= threshold.minPriceChangePercent && 
            hhChange2 >= threshold.minPriceChangePercent && 
            hasValidVolume -> Trend.UPTREND
            
            // 下降趨勢確認
            currentHigh < previousHigh && previousHigh < prePreviousHigh &&
            currentLow < previousLow && previousLow < prePreviousLow &&
            llChange1 >= threshold.minPriceChangePercent && 
            llChange2 >= threshold.minPriceChangePercent && 
            hasValidVolume -> Trend.DOWNTREND
            
            else -> Trend.NEUTRAL
        }
    }

    private fun analyzeEntryWithOrderBlocks(
        klines: List<Kline>,
        trend: Trend,
        mainOrderBlocks: List<OrderBlock>,
        intermediateOrderBlocks: List<OrderBlock>,
        mainFVGs: List<PriceRange>,
        intermediateFVGs: List<PriceRange>
    ) {
        val current = klines.last()
        val previous = klines[klines.size - 2]
        val currentPrice = current.closePrice.toDouble()
        
        // 計算當前價格與各個結構的距離
        val priceDistances = mutableListOf<Pair<String, Double>>()
        
        when (trend) {
            Trend.UPTREND -> {
                // 檢查是否在主要和中期的看漲訂單塊或FVG附近
                val bullishOBs = mainOrderBlocks.filter { it.type == OrderBlockType.BULLISH } +
                                intermediateOrderBlocks.filter { it.type == OrderBlockType.BULLISH }
                
                val bullishFVGs = mainFVGs.filter { it.type == GapType.BULLISH } +
                                 intermediateFVGs.filter { it.type == GapType.BULLISH }
                
                // 計算與訂單塊的距離
                bullishOBs.forEach { ob ->
                    if (isNearPrice(currentPrice, ob.startPrice, ob.endPrice)) {
                        val distance = abs(currentPrice - (ob.startPrice + ob.endPrice) / 2) / currentPrice * 100
                        priceDistances.add("OB" to distance)
                    }
                }
                
                // 計算與FVG的距離
                bullishFVGs.forEach { fvg ->
                    if (isNearPrice(currentPrice, fvg.start, fvg.end)) {
                        val distance = abs(currentPrice - (fvg.start + fvg.end) / 2) / currentPrice * 100
                        priceDistances.add("FVG(${String.format("%.1f", fvg.strength)}%)" to distance)
                    }
                }
                
                // 如果有足夠接近的結構，生成做多信號
                if (priceDistances.isNotEmpty()) {
                    // 計算信號強度 - 基於結構數量和距離
                    val signalStrength = calculateSignalStrength(priceDistances, bullishOBs, bullishFVGs)
                    
                    // 只有當信號強度足夠時才生成交易信號
                    if (signalStrength > 60) {
                        printTradeSignal(
                            current,
                            previous,
                            true, // 做多
                            signalStrength,
                            mainOrderBlocks,
                            mainFVGs
                        )
                    } else {
                        println("做多信號強度不足: $signalStrength/100，不產生交易信號")
                    }
                }
            }
            
            Trend.DOWNTREND -> {
                // 檢查是否在主要和中期的看跌訂單塊或FVG附近
                val bearishOBs = mainOrderBlocks.filter { it.type == OrderBlockType.BEARISH } +
                                intermediateOrderBlocks.filter { it.type == OrderBlockType.BEARISH }
                
                val bearishFVGs = mainFVGs.filter { it.type == GapType.BEARISH } +
                                 intermediateFVGs.filter { it.type == GapType.BEARISH }
                
                // 計算與訂單塊的距離
                bearishOBs.forEach { ob ->
                    if (isNearPrice(currentPrice, ob.startPrice, ob.endPrice)) {
                        val distance = abs(currentPrice - (ob.startPrice + ob.endPrice) / 2) / currentPrice * 100
                        priceDistances.add("OB" to distance)
                    }
                }
                
                // 計算與FVG的距離
                bearishFVGs.forEach { fvg ->
                    if (isNearPrice(currentPrice, fvg.start, fvg.end)) {
                        val distance = abs(currentPrice - (fvg.start + fvg.end) / 2) / currentPrice * 100
                        priceDistances.add("FVG(${String.format("%.1f", fvg.strength)}%)" to distance)
                    }
                }
                
                // 如果有足夠接近的結構，生成做空信號
                if (priceDistances.isNotEmpty()) {
                    // 計算信號強度 - 基於結構數量和距離
                    val signalStrength = calculateSignalStrength(priceDistances, bearishOBs, bearishFVGs)
                    
                    // 只有當信號強度足夠時才生成交易信號
                    if (signalStrength > 60) {
                        printTradeSignal(
                            current,
                            previous,
                            false, // 做空
                            signalStrength,
                            mainOrderBlocks,
                            mainFVGs
                        )
                    } else {
                        println("做空信號強度不足: $signalStrength/100，不產生交易信號")
                    }
                }
            }
            
            else -> { /* 中性趨勢不產生信號 */ }
        }
    }

    // 計算信號強度
    private fun calculateSignalStrength(
        priceDistances: List<Pair<String, Double>>,
        orderBlocks: List<OrderBlock>,
        fvgs: List<PriceRange>
    ): Double {
        // 基礎分數 - 基於結構數量
        var strength = 50.0 + (priceDistances.size * 5.0)
        
        // 根據距離調整分數 - 距離越近分數越高
        val avgDistance = priceDistances.map { it.second }.average()
        strength -= avgDistance * 2
        
        // 根據FVG強度調整分數
        val avgFVGStrength = fvgs.map { it.strength }.average()
        if (!avgFVGStrength.isNaN()) {
            strength += avgFVGStrength / 2
        }
        
        // 根據訂單塊強度調整分數
        val avgOBStrength = orderBlocks.map { it.strength }.average()
        if (!avgOBStrength.isNaN()) {
            strength += avgOBStrength / 2
        }
        
        // 確保分數在0-100範圍內
        return strength.coerceIn(0.0, 100.0)
    }

    // 檢查價格是否在指定範圍附近（允許一定的緩衝區）
    private fun isNearPrice(price: Double, rangeStart: Double, rangeEnd: Double): Boolean {
        val buffer = (rangeEnd - rangeStart) * 0.1 // 允許10%的緩衝區
        return price >= (rangeStart - buffer) && price <= (rangeEnd + buffer)
    }

    private fun findOrderBlocks(klines: List<Kline>, interval: String): List<OrderBlock> {
        val orderBlocks = mutableListOf<OrderBlock>()
        val threshold = orderBlockThresholds[interval] ?: orderBlockThresholds["1h"]!!
        
        println("開始尋找${interval}訂單塊，K線數量: ${klines.size}")
        
        // 需要至少5根K線才能形成訂單塊
        if (klines.size < 5) return orderBlocks
        
        // 獲取當前價格
        val currentPrice = klines.last().closePrice.toDouble()
        
        // 計算平均交易量
        val avgVolume = klines.takeLast(10).map { it.volume.toDouble() }.average()
        
        // 向後查找可能的訂單塊
        for (i in 4 until klines.size) {
            val candles = klines.subList(i - 4, i + 1)
            
            // 檢查是否有趨勢反轉
            val beforeTrend = detectMiniTrend(candles.subList(0, 3))
            val afterTrend = detectMiniTrend(candles.subList(2, 5))
            
            // 只有在趨勢反轉時才考慮訂單塊
            if (beforeTrend != Trend.NEUTRAL && afterTrend != Trend.NEUTRAL && beforeTrend != afterTrend) {
                val potentialOB = candles[2] // 反轉前的最後一根K線
                val obVolume = potentialOB.volume.toDouble()
                
                // 檢查交易量是否足夠大
                if (obVolume > avgVolume * threshold.minVolumeMagnitude) {
                    val isBullish = afterTrend == Trend.UPTREND
                    
                    // 根據趨勢方向確定訂單塊類型和價格範圍
                    val obType = if (isBullish) OrderBlockType.BULLISH else OrderBlockType.BEARISH
                    
                    // 對於看漲訂單塊，使用下跌K線的下半部分
                    // 對於看跌訂單塊，使用上漲K線的上半部分
                    val (startPrice, endPrice) = if (isBullish) {
                        val open = potentialOB.openPrice.toDouble()
                        val close = potentialOB.closePrice.toDouble()
                        val low = potentialOB.lowPrice.toDouble()
                        val mid = (open + close) / 2
                        Pair(low, mid)
                    } else {
                        val open = potentialOB.openPrice.toDouble()
                        val close = potentialOB.closePrice.toDouble()
                        val high = potentialOB.highPrice.toDouble()
                        val mid = (open + close) / 2
                        Pair(mid, high)
                    }
                    
                    // 計算訂單塊強度
                    val strength = calculateOrderBlockStrength(
                        potentialOB,
                        obVolume / avgVolume,
                        candles,
                        interval
                    )
                    
                    // 檢查訂單塊是否仍然有效（未被價格穿越）
                    val isValid = if (isBullish) {
                        currentPrice > endPrice // 價格在看漲訂單塊上方
                    } else {
                        currentPrice < startPrice // 價格在看跌訂單塊下方
                    }
                    
                    // 只添加有效的訂單塊
                    if (isValid) {
                        orderBlocks.add(OrderBlock(
                            startPrice = startPrice,
                            endPrice = endPrice,
                            volume = obVolume,
                            type = obType,
                            strength = strength,
                            creationTime = potentialOB.closeTime
                        ))
                    }
                }
            }
        }
        
        // 按強度排序並只保留最強的10個訂單塊
        val sortedBlocks = orderBlocks.sortedByDescending { it.strength }.take(10)
        
        if (sortedBlocks.isNotEmpty()) {
            println("找到 ${sortedBlocks.size} 個有效${interval}訂單塊:")
            sortedBlocks.forEach { ob ->
                println("類型: ${ob.type}, 範圍: ${String.format("%.4f", ob.startPrice)} - ${String.format("%.4f", ob.endPrice)}, 強度: ${String.format("%.2f", ob.strength)}")
            }
        } else {
            println("未找到任何有效${interval}訂單塊")
        }
        
        return sortedBlocks
    }

    // 檢測小趨勢方向
    private fun detectMiniTrend(candles: List<Kline>): Trend {
        if (candles.size < 3) return Trend.NEUTRAL
        
        val first = candles[0]
        val last = candles[candles.size - 1]
        
        val firstClose = first.closePrice.toDouble()
        val lastClose = last.closePrice.toDouble()
        
        // 計算收盤價變化百分比
        val changePercent = (lastClose - firstClose) / firstClose * 100
        
        return when {
            changePercent > 0.5 -> Trend.UPTREND
            changePercent < -0.5 -> Trend.DOWNTREND
            else -> Trend.NEUTRAL
        }
    }

    // 計算訂單塊強度
    private fun calculateOrderBlockStrength(
        ob: Kline,
        volumeRatio: Double,
        surroundingCandles: List<Kline>,
        interval: String
    ): Double {
        // 基礎分數 - 基於交易量
        var strength = 50.0 + (volumeRatio - 1) * 10
        
        // 根據K線實體大小調整分數
        val bodySize = abs(ob.closePrice.toDouble() - ob.openPrice.toDouble())
        val totalSize = ob.highPrice.toDouble() - ob.lowPrice.toDouble()
        val bodySizeRatio = if (totalSize > 0) bodySize / totalSize else 0.0
        
        // 實體越大，訂單塊越強
        strength += bodySizeRatio * 20
        
        // 根據時間週期調整權重
        strength *= when(interval) {
            "4h" -> 1.3  // 4小時圖的訂單塊更重要
            "1h" -> 1.1  // 1小時圖的訂單塊次之
            else -> 1.0  // 5分鐘圖的訂單塊權重正常
        }
        
        // 確保分數在0-100範圍內
        return strength.coerceIn(0.0, 100.0)
    }

    private fun findFairValueGaps(klines: List<Kline>, interval: String): List<PriceRange> {
        val gaps = mutableListOf<PriceRange>()
        println("開始尋找${interval} FVG，K線數量: ${klines.size}")
        
        // 獲取當前時間週期的FVG閾值
        val minGapPercent = fvgThresholds[interval] ?: 0.2
        
        // 需要至少3根K線才能形成FVG
        for (i in 2 until klines.size) {
            val first = klines[i - 2]
            val second = klines[i - 1]
            val third = klines[i]
            
            val firstHigh = first.highPrice.toDouble()
            val firstLow = first.lowPrice.toDouble()
            val secondHigh = second.highPrice.toDouble()
            val secondLow = second.lowPrice.toDouble()
            val thirdHigh = third.highPrice.toDouble()
            val thirdLow = third.lowPrice.toDouble()
            
            // 計算價格移動的方向
            val firstMove = second.closePrice.toDouble() - first.closePrice.toDouble()
            val secondMove = third.closePrice.toDouble() - second.closePrice.toDouble()
            
            // 看漲FVG：
            // 1. 第二根K線的低點高於第三根K線的高點
            // 2. 價格先下跌後上漲
            if (secondLow > thirdHigh && firstMove < 0 && secondMove > 0) {
                val gapSize = secondLow - thirdHigh
                val averagePrice = (firstHigh + thirdLow) / 2
                
                // 使用時間週期特定的閾值
                if (gapSize / averagePrice > minGapPercent / 100) {
                    gaps.add(PriceRange(
                        start = thirdHigh,
                        end = secondLow,
                        type = GapType.BULLISH,
                        strength = gapSize / averagePrice * 100, // 缺口強度
                        creationTime = third.closeTime // 記錄創建時間
                    ))
                }
            }
            
            // 看跌FVG：
            // 1. 第二根K線的高點低於第三根K線的低點
            // 2. 價格先上漲後下跌
            if (secondHigh < thirdLow && firstMove > 0 && secondMove < 0) {
                val gapSize = thirdLow - secondHigh
                val averagePrice = (firstLow + thirdHigh) / 2
                
                // 使用時間週期特定的閾值
                if (gapSize / averagePrice > minGapPercent / 100) {
                    gaps.add(PriceRange(
                        start = secondHigh,
                        end = thirdLow,
                        type = GapType.BEARISH,
                        strength = gapSize / averagePrice * 100, // 缺口強度
                        creationTime = third.closeTime // 記錄創建時間
                    ))
                }
            }
        }
        
        // 獲取當前價格
        val currentPrice = klines.last().closePrice.toDouble()
        
        // 過濾掉已經被價格穿越的FVG
        val validGaps = gaps.filter { gap ->
            when (gap.type) {
                GapType.BULLISH -> currentPrice < gap.end  // 價格在看漲FVG下方或內部
                GapType.BEARISH -> currentPrice > gap.start // 價格在看跌FVG上方或內部
            }
        }
        
        // 按缺口大小排序
        val sortedGaps = validGaps.sortedByDescending { abs(it.end - it.start) }
        
        // 只保留最近的10個FVG
        val recentGaps = sortedGaps.take(10)
        
        if (recentGaps.isNotEmpty()) {
            println("找到 ${recentGaps.size} 個有效${interval} FVG:")
            recentGaps.forEach { gap ->
                println("類型: ${gap.type}, 範圍: ${String.format("%.4f", gap.start)} - ${String.format("%.4f", gap.end)}, 強度: ${String.format("%.2f", gap.strength)}%")
            }
        } else {
            println("未找到任何有效${interval} FVG")
        }
        
        return recentGaps
    }

    private data class PriceRange(
        val start: Double,
        val end: Double,
        val type: GapType,
        val strength: Double = 0.0, // 缺口強度（百分比）
        val creationTime: Long = 0  // 創建時間
    )

    private enum class GapType {
        BULLISH, BEARISH
    }

    private fun printTradeSignal(
        current: Kline, 
        previous: Kline, 
        isLong: Boolean,
        signalStrength: Double,
        mainOrderBlocks: List<OrderBlock>,
        mainFVGs: List<PriceRange>
    ) {
        val riskManagement = calculateRiskManagement(
            current, previous, isLong, mainOrderBlocks, mainFVGs
        )
        
        // 計算當前價格與進場價格的差距
        val currentPrice = current.closePrice.toDouble()
        val entryPriceDiff = ((riskManagement.entryPrice - currentPrice) / currentPrice * 100)
        val entryPriceDiffStr = String.format("%.2f", abs(entryPriceDiff)) + "%"
        
        // 計算風險比例
        val riskPercentage = abs(riskManagement.entryPrice - riskManagement.stopLoss) / riskManagement.entryPrice * 100
        
        // 獲取相關的市場結構
        val relevantStructures = getRelevantStructures(isLong, mainOrderBlocks, mainFVGs, currentPrice)
        
        // 檢查當前比特幣市場狀態
        val btcMarketState = checkBitcoinMarketState(current, isLong)
        
        println(buildString {
            appendLine("========= BTC/USDT SMC分析信號 =========")
            appendLine("時間: ${formatTime(current.closeTime)}")
            appendLine("主趨勢: ${if (isLong) "上升" else "下降"}")
            appendLine("信號類型: ${if (isLong) "做多" else "做空"}")
            appendLine("信號強度: ${String.format("%.2f", signalStrength)}/100")
            appendLine("當前價格: ${String.format("%.2f", currentPrice)}")
            appendLine("價格區間: ${current.lowPrice} - ${current.highPrice}")
            
            // 添加比特幣市場狀態
            appendLine("比特幣市場狀態:")
            btcMarketState.forEach { line ->
                appendLine("- $line")
            }
            
            // 添加進場策略建議
            appendLine("進場策略:")
            if (abs(entryPriceDiff) < 0.2) {
                appendLine("- 建議立即進場，當前價格接近理想進場點")
                // 比特幣特有建議
                appendLine("- 考慮分批進場，首批60%，剩餘40%設置低於當前價格0.5%的限價單")
            } else if (entryPriceDiff > 0 && isLong || entryPriceDiff < 0 && !isLong) {
                appendLine("- 建議等待回調至 ${String.format("%.2f", riskManagement.entryPrice)} (距當前價格: $entryPriceDiffStr)")
                // 比特幣特有建議
                appendLine("- 設置價格提醒，BTC波動較大，可能快速達到目標價格")
            } else {
                appendLine("- 建議限價單掛單於 ${String.format("%.2f", riskManagement.entryPrice)} (距當前價格: $entryPriceDiffStr)")
                // 比特幣特有建議
                appendLine("- 同時設置止損單，BTC可能快速突破後回撤")
            }
            
            // 添加相關市場結構信息
            if (relevantStructures.isNotEmpty()) {
                appendLine("相關市場結構:")
                relevantStructures.forEach { structure ->
                    appendLine("- $structure")
                }
            }
            
            appendLine("風險管理:")
            // 比特幣特有的風險管理建議
            appendLine("- 建議風險: 帳戶資金的 ${if (signalStrength > 80) "1.5%" else "1%"} (BTC波動較大，建議降低風險)")
            appendLine("- 止損價格: ${String.format("%.2f", riskManagement.stopLoss)} (風險: ${String.format("%.2f", riskPercentage)}%)")
            appendLine("- 建議使用追蹤止損，設置${if (isLong) "下跌" else "上漲"}1%啟動，${if (isLong) "回撤" else "回落"}0.5%觸發")
            appendLine("- 目標位:")
            riskManagement.targets.forEachIndexed { index, target ->
                val targetRR = abs(target.price - riskManagement.entryPrice) / abs(riskManagement.entryPrice - riskManagement.stopLoss)
                appendLine("  ${index + 1}) ${String.format("%.2f", target.price)} (${String.format("%.1f", targetRR)}R, 平倉${target.percentage}%)")
            }
            appendLine("- 綜合風險報酬比: ${String.format("%.2f", riskManagement.riskRewardRatio)}")
            
            // 添加交易執行建議
            appendLine("執行建議:")
            appendLine("- ${if (riskManagement.riskRewardRatio >= 3) "強烈推薦" else if (riskManagement.riskRewardRatio >= 2) "建議執行" else "謹慎考慮"}該交易")
            appendLine("- 建議使用${if (signalStrength > 75) "市價單" else "限價單"}進場")
            appendLine("- 止損單類型: ${if (riskPercentage < 1.5) "市價止損" else "觸發止損"}")
            
            // 比特幣特有的額外建議
            appendLine("比特幣特有建議:")
            appendLine("- 注意資金費率，避免在資金費率過高時持有大量倉位")
            appendLine("- 關注BTC主導地位指數變化，主導地位上升時信號更可靠")
            appendLine("- 留意美聯儲消息和宏觀經濟數據，可能導致BTC大幅波動")
            appendLine("- 建議同時關注ETH/BTC比率，作為市場情緒參考")
            
            appendLine("=======================================")
        })

        // 觸發回調，通知有新的交易信號
        onTradeSignal?.invoke()
    }

    // 檢查比特幣市場狀態
    private fun checkBitcoinMarketState(current: Kline, isLong: Boolean): List<String> {
        val states = mutableListOf<String>()
        
        // 檢查交易量
        val volume = current.volume.toDouble()
        val quoteVolume = volume * current.closePrice.toDouble() // 計算USDT交易量
        
        if (quoteVolume > 500_000_000) { // 5億USDT
            states.add("交易量異常高，可能有大幅波動")
        } else if (quoteVolume < 100_000_000) { // 1億USDT
            states.add("交易量較低，可能缺乏持續動力")
        }
        
        // 檢查K線實體與影線比例
        val bodySize = abs(current.openPrice.toDouble() - current.closePrice.toDouble())
        val totalRange = current.highPrice.toDouble() - current.lowPrice.toDouble()
        val bodyRatio = if (totalRange > 0) bodySize / totalRange else 0.0
        
        if (bodyRatio > 0.8) {
            states.add("強勁的${if (current.closePrice > current.openPrice) "買入" else "賣出"}壓力，趨勢可能持續")
        } else if (bodyRatio < 0.3) {
            states.add("市場猶豫，存在較大不確定性")
        }
        
        // 根據當前趨勢添加建議
        if (isLong) {
            states.add("上升趨勢中，關注上方整數關口阻力位")
            // 檢查是否接近整數關口
            val nextRoundNumber = (current.closePrice.toDouble() / 1000).toInt() * 1000 + 1000
            if (nextRoundNumber - current.closePrice.toDouble() < 500) {
                states.add("接近${nextRoundNumber}整數關口，可能有阻力")
            }
        } else {
            states.add("下降趨勢中，關注下方整數關口支撐位")
            // 檢查是否接近整數關口
            val prevRoundNumber = (current.closePrice.toDouble() / 1000).toInt() * 1000
            if (current.closePrice.toDouble() - prevRoundNumber < 500) {
                states.add("接近${prevRoundNumber}整數關口，可能有支撐")
            }
        }
        
        return states
    }

    // 獲取與當前價格相關的市場結構
    private fun getRelevantStructures(
        isLong: Boolean,
        orderBlocks: List<OrderBlock>,
        fvgs: List<PriceRange>,
        currentPrice: Double
    ): List<String> {
        val structures = mutableListOf<String>()
        
        // 篩選相關的訂單塊
        val relevantOBs = orderBlocks.filter { ob ->
            if (isLong) {
                ob.type == OrderBlockType.BULLISH && 
                currentPrice > ob.startPrice && 
                currentPrice < ob.endPrice * 1.05
            } else {
                ob.type == OrderBlockType.BEARISH && 
                currentPrice < ob.endPrice && 
                currentPrice > ob.startPrice * 0.95
            }
        }
        
        // 篩選相關的FVG
        val relevantFVGs = fvgs.filter { fvg ->
            if (isLong) {
                fvg.type == GapType.BULLISH && 
                currentPrice > fvg.start && 
                currentPrice < fvg.end * 1.05
            } else {
                fvg.type == GapType.BEARISH && 
                currentPrice < fvg.end && 
                currentPrice > fvg.start * 0.95
            }
        }
        
        // 添加訂單塊信息
        relevantOBs.forEach { ob ->
            structures.add("${if (ob.type == OrderBlockType.BULLISH) "看漲" else "看跌"}訂單塊: ${String.format("%.2f", ob.startPrice)} - ${String.format("%.2f", ob.endPrice)}, 強度: ${String.format("%.1f", ob.strength)}")
        }
        
        // 添加FVG信息
        relevantFVGs.forEach { fvg ->
            structures.add("${if (fvg.type == GapType.BULLISH) "看漲" else "看跌"}FVG: ${String.format("%.2f", fvg.start)} - ${String.format("%.2f", fvg.end)}, 強度: ${String.format("%.1f", fvg.strength)}%")
        }
        
        return structures
    }

    private fun calculateRiskManagement(
        current: Kline,
        previous: Kline,
        isLong: Boolean,
        mainOrderBlocks: List<OrderBlock>,
        mainFVGs: List<PriceRange>
    ): RiskManagement {
        val entryPrice = current.closePrice.toDouble()
        
        // 根據SMC策略設置止損
        val stopLoss = calculateSmartStopLoss(
            current,
            previous,
            isLong,
            mainOrderBlocks,
            mainFVGs
        )
        
        // 計算風險金額
        val riskAmount = abs(entryPrice - stopLoss)
        
        // 根據市場結構設置多個目標位
        val targets = calculateSmartTargets(
            entryPrice,
            stopLoss,
            riskAmount,
            isLong,
            mainOrderBlocks,
            mainFVGs
        )
        
        // 計算綜合風險報酬比
        val avgTarget = targets.sumOf { it.price * it.percentage } / 
                       targets.sumOf { it.percentage.toDouble() }
        val riskRewardRatio = abs(avgTarget - entryPrice) / riskAmount

        return RiskManagement(entryPrice, stopLoss, targets, riskRewardRatio)
    }

    private fun calculateSmartStopLoss(
        current: Kline,
        previous: Kline,
        isLong: Boolean,
        mainOrderBlocks: List<OrderBlock>,
        mainFVGs: List<PriceRange>
    ): Double {
        val baseStopLoss = if (isLong) previous.lowPrice.toDouble() else previous.highPrice.toDouble()
        
        // 尋找最近的訂單塊或FVG作為保護止損
        val nearestStructure = if (isLong) {
            // 做多時，找下方最近的看漲訂單塊或FVG
            val nearestOB = mainOrderBlocks
                .filter { it.type == OrderBlockType.BULLISH && it.endPrice < current.lowPrice.toDouble() }
                .maxByOrNull { it.endPrice }
            
            val nearestFVG = mainFVGs
                .filter { it.type == GapType.BULLISH && it.end < current.lowPrice.toDouble() }
                .maxByOrNull { it.end }
            
            when {
                nearestOB != null && nearestFVG != null -> 
                    maxOf(nearestOB.endPrice, nearestFVG.end, baseStopLoss)
                nearestOB != null -> maxOf(nearestOB.endPrice, baseStopLoss)
                nearestFVG != null -> maxOf(nearestFVG.end, baseStopLoss)
                else -> baseStopLoss
            }
        } else {
            // 做空時，找上方最近的看跌訂單塊或FVG
            val nearestOB = mainOrderBlocks
                .filter { it.type == OrderBlockType.BEARISH && it.startPrice > current.highPrice.toDouble() }
                .minByOrNull { it.startPrice }
                
            val nearestFVG = mainFVGs
                .filter { it.type == GapType.BEARISH && it.start > current.highPrice.toDouble() }
                .minByOrNull { it.start }
                
            when {
                nearestOB != null && nearestFVG != null -> 
                    minOf(nearestOB.startPrice, nearestFVG.start, baseStopLoss)
                nearestOB != null -> minOf(nearestOB.startPrice, baseStopLoss)
                nearestFVG != null -> minOf(nearestFVG.start, baseStopLoss)
                else -> baseStopLoss
            }
        }
        
        // 添加額外的緩衝區
        return if (isLong) {
            nearestStructure * 0.997  // 下調0.3%作為緩衝
        } else {
            nearestStructure * 1.003  // 上調0.3%作為緩衝
        }
    }

    private fun calculateSmartTargets(
        entryPrice: Double,
        stopLoss: Double,
        riskAmount: Double,
        isLong: Boolean,
        mainOrderBlocks: List<OrderBlock>,
        mainFVGs: List<PriceRange>
    ): List<Target> {
        val targets = mutableListOf<Target>()
        
        if (isLong) {
            // 找出上方的阻力位（看跌訂單塊和FVG）
            val resistances = (
                mainOrderBlocks
                    .filter { it.type == OrderBlockType.BEARISH && it.startPrice > entryPrice }
                    .map { it.startPrice } +
                mainFVGs
                    .filter { it.type == GapType.BEARISH && it.start > entryPrice }
                    .map { it.start }
            ).sorted()
            
            // 根據阻力位設置目標
            when {
                resistances.isEmpty() -> {
                    // 如果沒有明顯阻力位，使用風險比例設置目標
                    targets.add(Target(entryPrice + riskAmount * 2, 50))  // 2R，平50%
                    targets.add(Target(entryPrice + riskAmount * 3, 30))  // 3R，平30%
                    targets.add(Target(entryPrice + riskAmount * 4, 20))  // 4R，平20%
                }
                else -> {
                    // 使用阻力位設置目標
                    var remainingPercentage = 100
                    resistances.take(3).forEachIndexed { index, resistance ->
                        val percentage = when (index) {
                            0 -> 50
                            1 -> 30
                            else -> remainingPercentage
                        }
                        remainingPercentage -= percentage
                        targets.add(Target(resistance * 0.997, percentage))  // 略低於阻力位
                    }
                }
            }
        } else {
            // 找出下方的支撐位（看漲訂單塊和FVG）
            val supports = (
                mainOrderBlocks
                    .filter { it.type == OrderBlockType.BULLISH && it.endPrice < entryPrice }
                    .map { it.endPrice } +
                mainFVGs
                    .filter { it.type == GapType.BULLISH && it.end < entryPrice }
                    .map { it.end }
            ).sortedDescending()
            
            // 根據支撐位設置目標
            when {
                supports.isEmpty() -> {
                    targets.add(Target(entryPrice - riskAmount * 2, 50))
                    targets.add(Target(entryPrice - riskAmount * 3, 30))
                    targets.add(Target(entryPrice - riskAmount * 4, 20))
                }
                else -> {
                    var remainingPercentage = 100
                    supports.take(3).forEachIndexed { index, support ->
                        val percentage = when (index) {
                            0 -> 50
                            1 -> 30
                            else -> remainingPercentage
                        }
                        remainingPercentage -= percentage
                        targets.add(Target(support * 1.003, percentage))  // 略高於支撐位
                    }
                }
            }
        }
        
        return targets
    }

    private enum class Trend {
        UPTREND, DOWNTREND, NEUTRAL
    }

    private fun formatTime(timestamp: Long): String {
        return Instant.ofEpochMilli(timestamp)
            .atZone(ZoneId.systemDefault())
            .toLocalDateTime()
            .toString()
    }

    // 獲取每個時間週期所需的K線數量
    fun getRequiredKlineCount(interval: String): Int {
        // 返回緩衝區大小加上一些額外的空間
        return (bufferSizes[interval] ?: 20) + 5
    }
} 