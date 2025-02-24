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

    // 為不同時間週期設置閾值
    private val thresholds = mapOf(
        "4h" to PriceThresholds(
            minPriceChangePercent = 1.5,    // 4小時圖要求更大的變動幅度
            minVolumeMultiplier = 1.8,      // 交易量需要高於平均的1.8倍
            minSwingPoints = 3              // 至少需要3個擺動點確認
        ),
        "1h" to PriceThresholds(
            minPriceChangePercent = 0.8,    // 1小時圖的變動幅度要求
            minVolumeMultiplier = 1.5,      // 交易量需要高於平均的1.5倍
            minSwingPoints = 2              // 至少需要2個擺動點確認
        ),
        "5m" to PriceThresholds(
            minPriceChangePercent = 0.3,    // 5分鐘圖的最小變動幅度
            minVolumeMultiplier = 1.3,      // 交易量需要高於平均的1.3倍
            minSwingPoints = 2              // 至少需要2個擺動點確認
        )
    )

    // 新增訂單塊和流動性區域的閾值設置
    private val orderBlockThresholds = mapOf(
        "4h" to OrderBlockThresholds(
            minVolumeMagnitude = 2.5,     // 訂單塊的最小交易量倍數
            maxCandleCount = 5,           // 向前尋找訂單塊的K線數量
            priceRejectionPercent = 1.2   // 價格拒絕區域的百分比
        ),
        "1h" to OrderBlockThresholds(
            minVolumeMagnitude = 2.0,
            maxCandleCount = 8,
            priceRejectionPercent = 0.8
        ),
        "5m" to OrderBlockThresholds(
            minVolumeMagnitude = 1.8,
            maxCandleCount = 12,
            priceRejectionPercent = 0.5
        )
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
        val strength: Double               // 訂單塊強度評分
    )

    private enum class OrderBlockType {
        BULLISH, BEARISH
    }

    fun analyze(kline: Kline, interval: String) {
        // 只處理已完成的K線，避免未完成K線的假信號
        if (kline.isClosed) {
            val buffer = klineBuffers[interval] ?: return
            buffer.add(kline)
            
            // 維護固定大小的緩衝區，移除最舊的數據
            if (buffer.size > (bufferSizes[interval] ?: 20)) {
                buffer.removeAt(0)
            }

            // 確保所有時間週期都有足夠的數據進行分析
            if (klineBuffers.all { it.value.size >= 3 }) {
                analyzeMarketStructure()
            }
        }
    }

    private fun analyzeMarketStructure() {
        // 獲取不同時間週期的K線數據
        val mainTimeframe = klineBuffers["4h"]!!      // 4小時圖判斷主趨勢
        val intermediateTimeframe = klineBuffers["1h"]!! // 1小時圖判斷中期趨勢
        val shortTimeframe = klineBuffers["5m"]!!     // 5分鐘圖尋找進場點

        // 第一步：分析主要時間週期（4小時圖）
        // 1. 尋找主要時間週期的訂單塊
        val mainOrderBlocks = findOrderBlocks(mainTimeframe, "4h")
        // 2. 尋找主要時間週期的公允價值缺口
        val mainFVGs = findFairValueGaps(mainTimeframe)
        // 3. 判斷主要趨勢方向
        val mainTrend = analyzeTrend(mainTimeframe, "4h")
        
        // 只有在主趨勢明確（非中性）時繼續分析
        if (mainTrend != Trend.NEUTRAL) {
            // 第二步：分析中期時間週期（1小時圖）
            val intermediateTrend = analyzeTrend(intermediateTimeframe, "1h")
            
            // 只有當主趨勢和中期趨勢一致時繼續分析
            if (mainTrend == intermediateTrend) {
                // 1. 尋找中期時間週期的訂單塊
                val intermediateOrderBlocks = findOrderBlocks(intermediateTimeframe, "1h")
                // 2. 尋找中期時間週期的公允價值缺口
                val intermediateFVGs = findFairValueGaps(intermediateTimeframe)
                
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
        val currentPrice = current.closePrice.toDouble()
        
        when (trend) {
            Trend.UPTREND -> {
                // 檢查是否在主要和中期的看漲訂單塊或FVG附近
                val nearMainBullishOB = mainOrderBlocks.any { ob -> 
                    ob.type == OrderBlockType.BULLISH && 
                    isNearPrice(currentPrice, ob.startPrice, ob.endPrice)
                }
                
                val nearIntermediateBullishOB = intermediateOrderBlocks.any { ob ->
                    ob.type == OrderBlockType.BULLISH &&
                    isNearPrice(currentPrice, ob.startPrice, ob.endPrice)
                }
                
                val nearMainBullishFVG = mainFVGs.any { gap ->
                    gap.type == GapType.BULLISH &&
                    isNearPrice(currentPrice, gap.start, gap.end)
                }
                
                val nearIntermediateBullishFVG = intermediateFVGs.any { gap ->
                    gap.type == GapType.BULLISH &&
                    isNearPrice(currentPrice, gap.start, gap.end)
                }
                
                // 同時滿足主要和中期時間週期的確認
                if ((nearMainBullishOB || nearMainBullishFVG) && 
                    (nearIntermediateBullishOB || nearIntermediateBullishFVG)) {
                    // 計算信號強度
                    val signalStrength = calculateSignalStrength(
                        mainOrderBlocks.filter { it.type == OrderBlockType.BULLISH },
                        intermediateOrderBlocks.filter { it.type == OrderBlockType.BULLISH },
                        currentPrice
                    )
                    
                    printTradeSignal(current, klines[klines.size - 2], true, signalStrength)
                }
            }
            
            Trend.DOWNTREND -> {
                // 檢查是否在主要和中期的看跌訂單塊或FVG附近
                val nearMainBearishOB = mainOrderBlocks.any { ob ->
                    ob.type == OrderBlockType.BEARISH &&
                    isNearPrice(currentPrice, ob.startPrice, ob.endPrice)
                }
                
                val nearIntermediateBearishOB = intermediateOrderBlocks.any { ob ->
                    ob.type == OrderBlockType.BEARISH &&
                    isNearPrice(currentPrice, ob.startPrice, ob.endPrice)
                }
                
                val nearMainBearishFVG = mainFVGs.any { gap ->
                    gap.type == GapType.BEARISH &&
                    isNearPrice(currentPrice, gap.start, gap.end)
                }
                
                val nearIntermediateBearishFVG = intermediateFVGs.any { gap ->
                    gap.type == GapType.BEARISH &&
                    isNearPrice(currentPrice, gap.start, gap.end)
                }
                
                // 同時滿足主要和中期時間週期的確認
                if ((nearMainBearishOB || nearMainBearishFVG) && 
                    (nearIntermediateBearishOB || nearIntermediateBearishFVG)) {
                    // 計算信號強度
                    val signalStrength = calculateSignalStrength(
                        mainOrderBlocks.filter { it.type == OrderBlockType.BEARISH },
                        intermediateOrderBlocks.filter { it.type == OrderBlockType.BEARISH },
                        currentPrice
                    )
                    
                    printTradeSignal(current, klines[klines.size - 2], false, signalStrength)
                }
            }
            else -> {} // 不操作
        }
    }

    // 檢查價格是否在指定範圍附近（允許一定的緩衝區）
    private fun isNearPrice(price: Double, rangeStart: Double, rangeEnd: Double): Boolean {
        val buffer = (rangeEnd - rangeStart) * 0.1 // 允許10%的緩衝區
        return price >= (rangeStart - buffer) && price <= (rangeEnd + buffer)
    }

    // 計算信號強度
    private fun calculateSignalStrength(
        mainOrderBlocks: List<OrderBlock>,
        intermediateOrderBlocks: List<OrderBlock>,
        currentPrice: Double
    ): Double {
        // 找到最近的訂單塊
        val nearestMainOB = mainOrderBlocks.minByOrNull { 
            minOf(abs(currentPrice - it.startPrice), abs(currentPrice - it.endPrice))
        }
        val nearestIntermediateOB = intermediateOrderBlocks.minByOrNull {
            minOf(abs(currentPrice - it.startPrice), abs(currentPrice - it.endPrice))
        }
        
        // 綜合評分（滿分100）
        var score = 0.0
        
        // 主時間週期訂單塊評分（最高60分）
        if (nearestMainOB != null) {
            score += nearestMainOB.strength * 0.6
        }
        
        // 中期時間週期訂單塊評分（最高40分）
        if (nearestIntermediateOB != null) {
            score += nearestIntermediateOB.strength * 0.4
        }
        
        return score
    }

    private fun findOrderBlocks(klines: List<Kline>, interval: String): List<OrderBlock> {
        val threshold = orderBlockThresholds[interval] ?: orderBlockThresholds["1h"]!!
        val orderBlocks = mutableListOf<OrderBlock>()
        
        // 計算基準交易量（用於判斷高交易量K線）
        val avgVolume = klines.takeLast(threshold.maxCandleCount)
            .map { it.volume.toDouble() }
            .average()

        // 向前尋找可能的訂單塊
        for (i in klines.size - 3 downTo maxOf(0, klines.size - threshold.maxCandleCount)) {
            val current = klines[i]
            val next = klines[i + 1]
            val afterNext = klines[i + 2]
            val currentVolume = current.volume.toDouble()
            
            // 判斷看漲訂單塊的條件：
            // 1. 當前K線成交量顯著高於平均
            // 2. 當前K線為陽線
            // 3. 下一根K線回調
            // 4. 之後開始反轉向上
            if (isBullishOrderBlock(current, next, afterNext, currentVolume, avgVolume, threshold)) {
                orderBlocks.add(OrderBlock(
                    startPrice = current.lowPrice.toDouble(),
                    endPrice = current.highPrice.toDouble(),
                    volume = currentVolume,
                    type = OrderBlockType.BULLISH,
                    strength = calculateOrderBlockStrength(currentVolume, avgVolume, current, next, interval)
                ))
            }
            
            // 判斷看跌訂單塊的條件：
            // 1. 當前K線成交量顯著高於平均
            // 2. 當前K線為陰線
            // 3. 下一根K線反彈
            // 4. 之後開始繼續下跌
            if (isBearishOrderBlock(current, next, afterNext, currentVolume, avgVolume, threshold)) {
                orderBlocks.add(OrderBlock(
                    startPrice = current.lowPrice.toDouble(),
                    endPrice = current.highPrice.toDouble(),
                    volume = currentVolume,
                    type = OrderBlockType.BEARISH,
                    strength = calculateOrderBlockStrength(currentVolume, avgVolume, current, next, interval)
                ))
            }
        }
        
        return orderBlocks
    }

    private fun isBullishOrderBlock(
        current: Kline,
        next: Kline,
        afterNext: Kline,
        currentVolume: Double,
        avgVolume: Double,
        threshold: OrderBlockThresholds
    ): Boolean {
        val currentClose = current.closePrice.toDouble()
        val currentOpen = current.openPrice.toDouble()
        val nextLow = next.lowPrice.toDouble()
        val afterNextLow = afterNext.lowPrice.toDouble()
        
        return currentVolume > avgVolume * threshold.minVolumeMagnitude && // 交易量條件
               currentClose > currentOpen && // 收陽
               nextLow < currentOpen && // 下一根下跌
               afterNextLow > nextLow && // 之後開始反轉
               (currentClose - currentOpen) / currentOpen * 100 > threshold.priceRejectionPercent // 足夠的漲幅
    }

    private fun isBearishOrderBlock(
        current: Kline,
        next: Kline,
        afterNext: Kline,
        currentVolume: Double,
        avgVolume: Double,
        threshold: OrderBlockThresholds
    ): Boolean {
        val currentClose = current.closePrice.toDouble()
        val currentOpen = current.openPrice.toDouble()
        val nextHigh = next.highPrice.toDouble()
        val afterNextHigh = afterNext.highPrice.toDouble()
        
        return currentVolume > avgVolume * threshold.minVolumeMagnitude && // 交易量條件
               currentClose < currentOpen && // 收陰
               nextHigh > currentOpen && // 下一根上漲
               afterNextHigh < nextHigh && // 之後開始反轉
               (currentOpen - currentClose) / currentOpen * 100 > threshold.priceRejectionPercent // 足夠的跌幅
    }

    private fun calculateOrderBlockStrength(
        volume: Double,
        avgVolume: Double,
        current: Kline,
        next: Kline,
        interval: String
    ): Double {
        // 基礎分數組成（總分100分）：
        // 1. 交易量強度（35分）- 提高權重因為BTC交易量很重要
        // 2. K線形態評分（25分）- 降低權重因為BTC波動較大
        // 3. 價格拒絕程度（25分）- 提高權重因為BTC的價格拒絕更明顯
        // 4. 市場反應評分（15分）- 降低權重因為BTC後續反應可能較劇烈

        // 1. 交易量強度評分（最高35分）
        val volumeRatio = volume / avgVolume
        val volumeScore = when (interval) {
            "4h" -> when {
                volumeRatio >= 3.5 -> 35.0  // BTC 4小時圖需要更大的交易量
                volumeRatio >= 3.0 -> 30.0
                volumeRatio >= 2.5 -> 25.0
                volumeRatio >= 2.0 -> 20.0
                else -> 15.0
            }
            "1h" -> when {
                volumeRatio >= 3.0 -> 35.0
                volumeRatio >= 2.5 -> 30.0
                volumeRatio >= 2.0 -> 25.0
                volumeRatio >= 1.5 -> 20.0
                else -> 15.0
            }
            else -> when {  // 5分鐘
                volumeRatio >= 2.5 -> 35.0
                volumeRatio >= 2.0 -> 30.0
                volumeRatio >= 1.5 -> 25.0
                volumeRatio >= 1.2 -> 20.0
                else -> 15.0
            }
        }

        // 2. K線形態評分（最高25分）
        val candleScore = calculateCandleScore(current, interval)

        // 3. 價格拒絕程度評分（最高25分）
        val rejectionScore = calculateRejectionScore(current, next, interval)

        // 4. 市場反應評分（最高15分）
        val marketReactionScore = calculateMarketReactionScore(current, next, interval)

        return volumeScore + candleScore + rejectionScore + marketReactionScore
    }

    // 評估K線形態 - 針對BTC調整
    private fun calculateCandleScore(kline: Kline, interval: String): Double {
        val open = kline.openPrice.toDouble()
        val close = kline.closePrice.toDouble()
        val high = kline.highPrice.toDouble()
        val low = kline.lowPrice.toDouble()
        
        val bodySize = abs(close - open)
        val upperWick = high - maxOf(open, close)
        val lowerWick = minOf(open, close) - low
        val totalSize = high - low

        val bodySizeRatio = bodySize / totalSize
        val wickRatio = (upperWick + lowerWick) / totalSize

        // BTC的K線通常波動較大，所以放寬標準
        return when (interval) {
            "4h" -> when {
                bodySizeRatio >= 0.65 && wickRatio <= 0.35 -> 25.0
                bodySizeRatio >= 0.55 && wickRatio <= 0.45 -> 20.0
                bodySizeRatio >= 0.45 && wickRatio <= 0.55 -> 15.0
                else -> 10.0
            }
            "1h" -> when {
                bodySizeRatio >= 0.60 && wickRatio <= 0.40 -> 25.0
                bodySizeRatio >= 0.50 && wickRatio <= 0.50 -> 20.0
                bodySizeRatio >= 0.40 && wickRatio <= 0.60 -> 15.0
                else -> 10.0
            }
            else -> when {  // 5分鐘
                bodySizeRatio >= 0.55 && wickRatio <= 0.45 -> 25.0
                bodySizeRatio >= 0.45 && wickRatio <= 0.55 -> 20.0
                bodySizeRatio >= 0.35 && wickRatio <= 0.65 -> 15.0
                else -> 10.0
            }
        }
    }

    // 評估價格拒絕程度 - 針對BTC調整
    private fun calculateRejectionScore(current: Kline, next: Kline, interval: String): Double {
        val currentClose = current.closePrice.toDouble()
        val currentOpen = current.openPrice.toDouble()
        val currentHigh = current.highPrice.toDouble()
        val currentLow = current.lowPrice.toDouble()
        
        val nextHigh = next.highPrice.toDouble()
        val nextLow = next.lowPrice.toDouble()
        
        // 計算當前K線的移動幅度和整體範圍
        val currentMove = abs(currentClose - currentOpen)
        val currentRange = currentHigh - currentLow
        
        // 計算實體佔整體範圍的比例（用於評估拒絕的質量）
        val bodyToRangeRatio = currentMove / currentRange
        
        // 計算價格拒絕的百分比
        val rejectionPercent = (currentMove / currentOpen) * 100
        
        // 計算下一根K線的反應程度
        val isBullish = currentClose > currentOpen
        val nextReaction = if (isBullish) {
            (currentClose - nextLow) / currentClose * 100
        } else {
            (nextHigh - currentClose) / currentClose * 100
        }

        // 當前K線評分（最高15分）：
        // - 價格拒絕幅度（10分）
        // - 實體佔比質量（5分）
        val rejectionScore = when (interval) {
            "4h" -> when {
                rejectionPercent >= 3.0 -> 10.0
                rejectionPercent >= 2.0 -> 8.0
                rejectionPercent >= 1.5 -> 6.0
                else -> 4.0
            }
            "1h" -> when {
                rejectionPercent >= 2.0 -> 10.0
                rejectionPercent >= 1.5 -> 8.0
                rejectionPercent >= 1.0 -> 6.0
                else -> 4.0
            }
            else -> when {
                rejectionPercent >= 1.5 -> 10.0
                rejectionPercent >= 1.0 -> 8.0
                rejectionPercent >= 0.7 -> 6.0
                else -> 4.0
            }
        }

        // 實體佔比評分
        val qualityScore = when {
            bodyToRangeRatio >= 0.7 -> 5.0  // 高質量的價格拒絕
            bodyToRangeRatio >= 0.5 -> 4.0
            bodyToRangeRatio >= 0.3 -> 3.0
            else -> 2.0
        }

        // 下一根K線的反應評分（最高10分）
        val nextScore = when (interval) {
            "4h" -> when {
                nextReaction >= 2.0 -> 10.0
                nextReaction >= 1.5 -> 8.0
                nextReaction >= 1.0 -> 6.0
                else -> 4.0
            }
            "1h" -> when {
                nextReaction >= 1.5 -> 10.0
                nextReaction >= 1.0 -> 8.0
                nextReaction >= 0.7 -> 6.0
                else -> 4.0
            }
            else -> when {
                nextReaction >= 1.0 -> 10.0
                nextReaction >= 0.7 -> 8.0
                nextReaction >= 0.5 -> 6.0
                else -> 4.0
            }
        }

        return rejectionScore + qualityScore + nextScore
    }

    // 評估市場反應 - 針對BTC調整
    private fun calculateMarketReactionScore(
        current: Kline, 
        next: Kline, 
        interval: String
    ): Double {
        val currentClose = current.closePrice.toDouble()
        val currentOpen = current.openPrice.toDouble()
        val nextHigh = next.highPrice.toDouble()
        val nextLow = next.lowPrice.toDouble()
        
        val isBullish = currentClose > currentOpen
        val reactionMove = if (isBullish) {
            (currentOpen - nextLow) / currentOpen * 100
        } else {
            (nextHigh - currentOpen) / currentOpen * 100
        }

        // BTC的反應幅度通常較大
        return when (interval) {
            "4h" -> when {
                reactionMove >= 2.0 -> 15.0
                reactionMove >= 1.5 -> 12.0
                reactionMove >= 1.0 -> 9.0
                else -> 6.0
            }
            "1h" -> when {
                reactionMove >= 1.5 -> 15.0
                reactionMove >= 1.0 -> 12.0
                reactionMove >= 0.7 -> 9.0
                else -> 6.0
            }
            else -> when {  // 5分鐘
                reactionMove >= 1.0 -> 15.0
                reactionMove >= 0.7 -> 12.0
                reactionMove >= 0.5 -> 9.0
                else -> 6.0
            }
        }
    }

    private fun findFairValueGaps(klines: List<Kline>): List<PriceRange> {
        val gaps = mutableListOf<PriceRange>()
        
        // 需要至少3根K線才能形成FVG
        for (i in 2 until klines.size) {
            val first = klines[i - 2]
            val second = klines[i - 1]
            val third = klines[i]
            
            // 看漲FVG：第一根K線的低點高於第三根K線的高點
            if (first.lowPrice.toDouble() > third.highPrice.toDouble()) {
                gaps.add(PriceRange(
                    start = third.highPrice.toDouble(),
                    end = first.lowPrice.toDouble(),
                    type = GapType.BULLISH
                ))
            }
            
            // 看跌FVG：第一根K線的高點低於第三根K線的低點
            if (first.highPrice.toDouble() < third.lowPrice.toDouble()) {
                gaps.add(PriceRange(
                    start = first.highPrice.toDouble(),
                    end = third.lowPrice.toDouble(),
                    type = GapType.BEARISH
                ))
            }
        }
        
        return gaps
    }

    private data class PriceRange(
        val start: Double,
        val end: Double,
        val type: GapType
    )

    private enum class GapType {
        BULLISH, BEARISH
    }

    private fun printTradeSignal(
        current: Kline, 
        previous: Kline, 
        isLong: Boolean,
        signalStrength: Double
    ) {
        println(buildString {
            appendLine("========= SMC多時間週期分析信號 =========")
            appendLine("時間: ${formatTime(current.closeTime)}")
            appendLine("主趨勢: ${if (isLong) "上升" else "下降"}")
            appendLine("信號類型: ${if (isLong) "做多" else "做空"}")
            appendLine("信號強度: ${String.format("%.2f", signalStrength)}/100")
            appendLine("價格區間: ${current.lowPrice} - ${current.highPrice}")
            appendLine("進場價格: ${current.closePrice}")
            appendLine("風險管理:")
            appendLine("- 止損: ${if (isLong) previous.lowPrice else previous.highPrice}")
            appendLine("- 目標: ${calculateTarget(
                current.closePrice.toDouble(),
                if (isLong) previous.lowPrice.toDouble() else previous.highPrice.toDouble(),
                isLong
            )}")
            appendLine("=======================================")
        })
    }

    private enum class Trend {
        UPTREND, DOWNTREND, NEUTRAL
    }

    private fun calculateTarget(entryPrice: Double, stopLoss: Double, isLong: Boolean): String {
        val riskAmount = if (isLong) entryPrice - stopLoss else stopLoss - entryPrice
        val target = if (isLong) {
            entryPrice + (riskAmount * 2) // 2:1的獲利比率
        } else {
            entryPrice - (riskAmount * 2)
        }
        return String.format("%.2f", target)
    }

    private fun formatTime(timestamp: Long): String {
        return Instant.ofEpochMilli(timestamp)
            .atZone(ZoneId.systemDefault())
            .toLocalDateTime()
            .toString()
    }
} 