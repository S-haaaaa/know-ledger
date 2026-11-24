package net.ankio.ledger.core

/**
 * Fast-exit fallback when a validated payment success screen disappears before screenshot capture completes.
 * Only operates on accessibility texts already verified by [PaymentPageDetector].
 */
object AccessibilityBillFallback {
    const val REASON = "页面切换较快，已通过页面文字快速记账（无原图，待补充图标）"

    private val defaultMerchants = setOf("微信支出", "支付宝支出", "美团支出", "淘宝支出", "拼多多支出", "京东支出", "支出记账", "微信收入", "支付宝收入", "收入记账")
    private val uiOrPaymentTerms = Regex(
        "^(?:支付成功|付款成功|转账成功|交易成功|扣款成功|成功扣款|已支付|已付款|已转账|已存入对方零钱|转账已发起|" +
            "等待对方收款|待对方收款|对方已收钱|对方已收款|已被接收|购买成功|下单成功|拼单成功|" +
            "完成|返回商家|返回商户|返回应用|完成付款|继续购物|查看订单|返回首页|回到首页|继续逛逛|去逛逛|" +
            "微信支付|微信零钱|支付宝|支付宝支付|美团支付|美团月付|多多支付|京东支付|白条|白条支付|先用后付|" +
            "零钱|零钱通|余额|余额宝|花呗|银行卡|" +
            "支付方式|付款方式|当前状态|交易状态|账单服务|收款方服务|对订单有疑惑|电子凭证|更多服务|查看账单|全部账单|" +
            "账单详情|交易详情|转账详情|订单信息|付款金额|支付金额|实付款|实付金额|实付合计|合计实付|实际付款|实际支付|实付|共支付|" +
            "转账金额|支付时间|付款时间|转账时间|交易时间|" +
            "交易单号|转账单号|支付宝交易号|交易号|订单编号|订单号|收款方备注|付款方备注|二维码收款|付款给商家)[：:]*$"
    )
    private val promoTerms = Regex("优惠|红包|立减|折扣|优惠券|代金券|抵扣券|积分|领取|领券|奖励|返现|补贴|免运费|好评率|进店|猜你喜欢")
    private val dateLike = Regex("20[0-9]{2}[年/.-]|[0-9]{1,2}:[0-9]{2}")
    private val idLike = Regex("^[A-Za-z0-9_-]{16,160}$")
    private val moneyLike = Regex("^[+\\-−¥￥\\s]*[0-9]{1,3}(?:,[0-9]{3})*(?:\\.[0-9]{1,2})?[元]?$")
    private val transferRecipientPatterns = listOf(
        Regex("^转账给(.{1,40})$"),
        Regex("^已向(.{1,40}?)转账$"),
        Regex("^已存入(.{1,40}?)(?:的)?零钱$"),
        Regex("^(?:待|等待)(.{1,40}?)(?:确认)?收[款钱]$"),
        Regex("^给(.{1,40})$"),
    )
    private val genericRecipientTokens = setOf("对方", "你", "我", "商家", "商户", "收款人", "收款方")
    private val fieldPrefix = Regex("^(?:实付款|实付金额|实付合计|合计实付|实际付款|实际支付|实付(?!价)|支付金额|付款金额|共支付|转账金额|支付时间|付款时间|转账时间|交易时间|交易单号|转账单号|支付宝交易号|交易号|订单编号|订单号|支付方式|付款方式|当前状态|交易状态|备注)[：:]")

    fun parse(match: PaymentMatch, capturedAt: Long): ParsedBill? {
        val amount = match.amountCents?.takeIf { it > 0 } ?: return null
        val rawTexts = match.texts.map(String::trim).filter(String::isNotBlank)
        if (rawTexts.isEmpty() || !PaymentApps.supported(match.packageName)) return null
        val page = PaymentPage(match.packageName, match.windowId, match.activity, rawTexts)
        if (CapturePrivacy.sensitive(page) || !PaymentPageDetector.eligibleActivity(match.packageName, match.activity)) return null

        val rawJoined = rawTexts.joinToString("\n")
        if (TransactionRejection.reason(rawJoined, manualCapture = false) != null) return null

        val lines = rawTexts.map { LocalOcrLine(it) }
        var parsed = LocalOcrBillParser.parse(
            lines = lines,
            width = 0,
            height = 0,
            capturedAt = capturedAt,
            livePaymentAt = capturedAt,
            expenseOnly = true,
            origin = match.packageName,
        )
        if (parsed?.amountCents == null) {
            // PaymentPageDetector accepts bare amounts like "12.34", while LocalOcrBillParser with width=0
            // requires a currency marker when no explicit amount label exists. Retry with the verified amount.
            val augmentedLines = lines + LocalOcrLine("¥${Money.display(amount)}")
            parsed = LocalOcrBillParser.parse(
                lines = augmentedLines,
                width = 0,
                height = 0,
                capturedAt = capturedAt,
                livePaymentAt = capturedAt,
                expenseOnly = true,
                origin = match.packageName,
            )
        }
        val base = parsed ?: return null
        if (base.direction != Direction.EXPENSE || base.amountCents != amount || base.refund != null) return null

        val merchant = if (base.merchant !in defaultMerchants && base.merchant.isNotBlank()) {
            base.merchant
        } else {
            extractFallbackMerchant(rawTexts) ?: base.merchant
        }
        val category = LocalOcrBillParser.inferCategory(lines, merchant)
        val transactionId = base.transactionId ?: match.transactionId
        return base.copy(
            merchant = merchant,
            category = category,
            transactionId = transactionId,
            reliable = true,
            reason = REASON,
        )
    }

    private fun extractFallbackMerchant(texts: List<String>): String? {
        for (raw in texts) {
            val cleaned = raw.trim().trim('|', '丨').trim()
            if (cleaned.length !in 2..80) continue
            val compact = cleaned.filterNot(Char::isWhitespace)
            val recipientMatch = transferRecipientPatterns.firstNotNullOfOrNull { pattern ->
                pattern.matchEntire(compact)?.groupValues?.drop(1)?.firstOrNull { it.isNotBlank() }
            }
            if (recipientMatch != null) {
                val candidate = recipientMatch.trim()
                if (candidate.length in 2..80 && candidate !in genericRecipientTokens &&
                    !uiOrPaymentTerms.matches(candidate) && !LocalOcrBillParser.isMerchantInterfaceText(candidate) &&
                    !dateLike.containsMatchIn(candidate) && candidate.any { it.isLetter() }
                ) {
                    return candidate
                }
                continue
            }
            if (Regex("^已存入.{1,40}(?:的)?零钱$").matches(compact)) continue
            if (uiOrPaymentTerms.matches(compact)) continue
            if (fieldPrefix.containsMatchIn(compact)) continue
            if (promoTerms.containsMatchIn(compact)) continue
            if (moneyLike.matches(compact) || Money.parseCents(compact.trimStart('+', '-', '−', '¥', '￥').trimEnd('元')) != null) continue
            if (dateLike.containsMatchIn(compact) || idLike.matches(compact)) continue
            if (LocalOcrBillParser.isMerchantInterfaceText(compact)) continue
            if (compact.all { !it.isLetter() }) continue
            return cleaned
        }
        return null
    }
}
