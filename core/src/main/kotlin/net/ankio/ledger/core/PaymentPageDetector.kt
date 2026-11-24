package net.ankio.ledger.core

data class PaymentPage(val packageName: String, val windowId: Int, val activity: String, val texts: List<String>, val editable: Boolean = false)
data class PaymentMatch(
    val signature: String,
    val transactionId: String?,
    val packageName: String,
    val windowId: Int,
    val activity: String,
    val eventIdentity: String = signature,
    val texts: List<String> = emptyList(),
    val amountCents: Long? = null,
)

object PaymentPageDetector {
    const val WECHAT_FRAMEWORK_RESULT = "com.tencent.mm.framework.app.UIPageFragmentActivity"
    const val WECHAT_LITE_RESULT = "com.tencent.mm.plugin.lite.ui.WxaLiteAppTransparentLiteUI"
    const val WECHAT_LITE_OPAQUE_RESULT = "com.tencent.mm.plugin.lite.ui.WxaLiteAppLiteUI"
    private val dedicatedWechatResults = setOf(
        "com.tencent.mm.plugin.remittance.ui.RemittanceResultNewUI",
        "com.tencent.mm.plugin.remittance.ui.RemittanceResultUI",
        "com.tencent.mm.plugin.remittance.ui.RemittanceResultOldUI",
        "com.tencent.mm.plugin.remittance.ui.RemittanceOSUI",
    )
    private val wechatTransferResultActivities = dedicatedWechatResults + setOf(
        WECHAT_FRAMEWORK_RESULT,
        WECHAT_LITE_RESULT,
        WECHAT_LITE_OPAQUE_RESULT,
    )
    private val successStatuses = setOf(
        "支付成功",
        "付款成功",
        "交易成功",
        "转账成功",
        "已转账",
        "已存入对方零钱",
        "转账已发起",
        "购买成功",
        "对方已收钱",
        "对方已收款",
        "已被接收",
    )
    private val ecommerceOrderSuccessStatuses = setOf("下单成功", "拼单成功")
    private val incomingTransferTerms = setOf(
        "确认收款",
        "确认收钱",
        "立即收款",
        "待收款",
        "待你收款",
        "待我收款",
        "已收款",
        "已收钱",
        "收款成功",
        "已存入零钱",
        "收款到账",
    )
    private val historicalTexts = setOf("全部账单", "账单详情", "交易详情", "转账详情", "账单服务", "订单详情")
    private val prohibited = listOf(
        "请输入支付密码",
        "输入密码",
        "确认支付",
        "确认付款",
        "支付失败",
        "付款失败",
        "转账失败",
        "交易失败",
        "交易关闭",
        "已退还",
        "退款中",
        "待支付",
        "待付款",
        "等待付款",
        "提交订单",
        "确认订单",
        "去支付",
        "立即支付",
        "去付款",
        "先用后付",
        "0元下单",
        "确认收货后再付款",
        "尚未扣款",
        "货到付款",
    )
    private val transactionLabels = listOf("交易单号", "转账单号", "支付宝交易号", "交易号", "订单编号", "订单号")
    private val actualAmountLabels = listOf(
        "实付款",
        "实付金额",
        "实付合计",
        "合计实付",
        "实际付款",
        "实际支付",
        "支付金额",
        "付款金额",
        "转账金额",
        "收款金额",
        "共支付",
        "实付",
    )
    private val promotionLabels = listOf("领取", "领券", "优惠券", "代金券", "抵扣券", "红包", "积分", "返现", "立减", "补贴", "优惠金额")
    private val idPattern = Regex("[0-9A-Za-z]{16,64}")
    private val bareAmount = Regex("[+\\-−]?[0-9]{1,9}\\.[0-9]{2}")
    private val currencyAmount = Regex("[¥][+\\-−]?([0-9]{1,9}(?:\\.[0-9]{1,2})?)|([0-9]{1,9}(?:\\.[0-9]{1,2})?)元")

    private val historyActivity = Regex("detail|orderinfo|billhistory|transactionrecord",RegexOption.IGNORE_CASE)
    private val whitespace = Regex("\\s+")
    private val receivedStatus = Regex("^.{1,40}已收[款钱]$")
    private val outgoingTransferStatus = Regex("^(?:已向.{1,40}转账|已存入.{1,40}(?:的)?零钱|向.{1,40}转账成功|转账给.{1,40}成功)$")
    private val awaitingRecipientStatus = Regex("^(?:待|等待).{1,40}(?:确认)?收[款钱]$")
    private val successWithMessage = Regex("^(支付成功|付款成功|转账成功|交易成功|已转账|购买成功)[，,:：].{0,80}$")
    private val clockText = Regex("\\d{1,2}:\\d{2}(:\\d{2})?")

    fun eligibleActivity(packageName: String, activity: String): Boolean = when (packageName) {
        PaymentApps.WECHAT -> activity in setOf(WECHAT_FRAMEWORK_RESULT,WECHAT_LITE_RESULT,WECHAT_LITE_OPAQUE_RESULT) || activity.startsWith("com.tencent.mm.plugin.") &&
            listOf(".wallet.", ".wallet_core.", ".remittance.", ".wxpay.", ".webview.ui.tools.").any(activity::contains)
        PaymentApps.ALIPAY -> activity.startsWith("com.alipay.") || activity.startsWith("${PaymentApps.ALIPAY}.")
        // An SDK or e-commerce result may live in host/payment containers; content validation below is stricter.
        PaymentApps.MEITUAN -> listOf("com.sankuai.", "com.meituan.", "com.dianping.", "com.alipay.").any(activity::startsWith)
        PaymentApps.TAOBAO -> listOf("com.taobao.", "com.alibaba.", "com.tmall.", "com.alipay.").any(activity::startsWith)
        PaymentApps.PINDUODUO -> listOf("com.xunmeng.", "com.alipay.").any(activity::startsWith)
        PaymentApps.JD -> listOf("com.jingdong.", "com.jd.", "com.jdpaysdk.", "com.wangyin.", "com.alipay.").any(activity::startsWith)
        else -> false
    }

    fun detect(page: PaymentPage, visitId: String? = null): PaymentMatch? {
        if (CapturePrivacy.sensitive(page) || !eligibleActivity(page.packageName, page.activity)) return null
        // Automatic capture is the immediate payment result, never a historical bill opened
        // by the user. Details remain an explicit shake/import operation.
        if(historyActivity.containsMatchIn(page.activity))return null
        val texts = page.texts.map { normalize(it).replace(whitespace, "") }
            .filter(String::isNotBlank).distinct()
        if(texts.any { it in historicalTexts })return null
        // Never request pixels to discover whether an unreadable container is a result
        // or a password page. Success evidence is required BEFORE the screenshot API.
        val dedicated = page.packageName == PaymentApps.WECHAT && page.activity in dedicatedWechatResults
        if (texts.isEmpty()) return null
        val joined = texts.joinToString("|")
        if (prohibited.any(joined::contains)) return null
        val completionControl = texts.any {
            it in setOf("完成", "返回商家", "返回商户", "返回应用", "完成付款", "继续购物", "查看订单", "返回首页", "回到首页", "继续逛逛", "去逛逛")
        }
        val hasIncomingTransfer = texts.any { it in incomingTransferTerms }
        val ecommerce = page.packageName in PaymentApps.ecommercePackages
        val standardSuccess = texts.any { value ->
            val status = value.removePrefix("当前状态").removePrefix("交易状态").trimStart(':', '：')
            status in successStatuses ||
                (dedicated && receivedStatus.matches(status)) ||
                outgoingTransferStatus.matches(status) ||
                successWithMessage.matches(status)
        }
        val wechatAwaitingTransfer = page.packageName == PaymentApps.WECHAT &&
            page.activity in wechatTransferResultActivities &&
            completionControl &&
            !hasIncomingTransfer &&
            texts.any { value ->
                val status = value.removePrefix("当前状态").removePrefix("交易状态").trimStart(':', '：')
                awaitingRecipientStatus.matches(status)
            }
        val ecommerceOrderSuccess = ecommerce && texts.any { value ->
            val status = value.removePrefix("当前状态").removePrefix("交易状态").trimStart(':', '：')
            status in ecommerceOrderSuccessStatuses
        }
        if (hasIncomingTransfer && !standardSuccess) return null
        if (!standardSuccess && !wechatAwaitingTransfer && !ecommerceOrderSuccess) return null
        val actualAmounts = texts.flatMapIndexed { index, text ->
            actualAmountLabels.firstOrNull { label ->
                text.startsWith(label) && !(label == "实付" && text.startsWith("实付价"))
            }?.let { label ->
                val own = amounts(text.removePrefix(label).trimStart(':', '：'))
                if (own.isNotEmpty()) own else amounts(texts.getOrNull(index + 1).orEmpty())
            }.orEmpty()
        }.distinct()
        // SDK result containers are shared by merchants. Their coupon/reward cards
        // are not additional payments; retain every nonpromotional amount so that
        // genuinely ambiguous totals still fail closed instead of choosing one.
        val allAmounts = texts.filterNot { text ->
            promotionLabels.any(text::contains) && actualAmountLabels.none(text::contains)
        }.flatMap(::amounts).distinct()
        // An explicit paid total disambiguates original price/discount on a result screen.
        val selectedAmounts = if (actualAmounts.isNotEmpty()) actualAmounts else allAmounts
        if (selectedAmounts.size != 1 || selectedAmounts.single() <= 0) return null
        if (ecommerce && ecommerceOrderSuccess && !standardSuccess && actualAmounts.size != 1) return null
        val transaction = transactionId(texts)
        val hasTime = listOf("支付时间", "付款时间", "转账时间", "交易时间", "收款时间").any(joined::contains)
        val genericWeb = listOf("webview", "webactivity", "h5", "nebula", "browser").any(page.activity.lowercase()::contains)
        if (ecommerce) {
            // Product/order pages are insufficient. Require a paid total or transaction structure.
            if (actualAmounts.size != 1 && !(transaction != null && hasTime)) return null
        } else if (genericWeb && !(completionControl && actualAmounts.size==1)) return null
        return match(page, texts, transaction, selectedAmounts.single(), visitId)
    }

    private fun amounts(text: String): List<Long> = (if (bareAmount.matches(text)) {
        listOf(text.trimStart('+', '-', '−'))
    } else currencyAmount.findAll(text).map { it.groupValues[1].ifBlank { it.groupValues[2] } }.toList())
        .mapNotNull(Money::parseCents)

    private fun transactionId(texts: List<String>): String? = texts.firstNotNullOfOrNull { text ->
        transactionLabels.firstNotNullOfOrNull { label ->
            if (!text.startsWith(label)) null
            else text.removePrefix(label).trimStart(':', '：').takeIf(idPattern::matches)
        }
    } ?: texts.indices.firstNotNullOfOrNull { index ->
        if (texts[index].trimEnd(':', '：') !in transactionLabels) null
        else texts.getOrNull(index + 1)?.takeIf(idPattern::matches)
    }

    private fun match(page: PaymentPage, texts: List<String>, transaction: String?, amountCents: Long, visitId: String?): PaymentMatch {
        val canonical = if (transaction != null) "${page.packageName}|transaction:$transaction" else {
            val content = texts.filterNot { clockText.matches(it) }.sorted().joinToString("|")
            "${page.packageName}|${page.windowId}|${page.activity}|$content"
        }
        fun hash(value: String) = java.security.MessageDigest.getInstance("SHA-256").digest(value.toByteArray())
            .joinToString("") { "%02x".format(it) }
        // Full content still protects the screenshot boundary. Promotions may refresh
        // inside a retained result window without creating another payment event.
        val signature = hash(canonical)
        val identity = if (transaction != null) signature else
            hash("${page.packageName}|${visitId ?: "${page.windowId}|${page.activity}"}|amount:$amountCents")
        return PaymentMatch(
            signature = signature,
            transactionId = transaction,
            packageName = page.packageName,
            windowId = page.windowId,
            activity = page.activity,
            eventIdentity = identity,
            texts = page.texts.map(String::trim).filter(String::isNotBlank),
            amountCents = amountCents,
        )
    }
}

/** Claim the first valid frame immediately. This gate deduplicates events, not ledger bills. */
class PaymentGate {
    private var current: String? = null
    private val claimed = linkedSetOf<String>()
    @Synchronized fun observe(match: PaymentMatch?): Boolean {
        current = match?.eventIdentity
        return match != null && match.eventIdentity !in claimed
    }
    @Synchronized fun claim(match: PaymentMatch): Boolean {
        if (current != match.eventIdentity || match.eventIdentity in claimed) return false
        claimed += match.eventIdentity
        if (claimed.size > 256) claimed.remove(claimed.first())
        return true
    }
    @Synchronized fun release(match: PaymentMatch) { claimed.remove(match.eventIdentity) }
    @Synchronized fun reset() { current=null;claimed.clear() }
}
