package net.ankio.ledger.core

data class PaymentApp(val packageName: String, val label: String)

/** Supported adapters are an explicit allow-list, not permission to inspect every installed app. */
object PaymentApps {
    const val WECHAT = "com.tencent.mm"
    const val ALIPAY = "com.eg.android.AlipayGphone"
    const val MEITUAN = "com.sankuai.meituan"
    const val TAOBAO = "com.taobao.taobao"
    const val PINDUODUO = "com.xunmeng.pinduoduo"
    const val JD = "com.jingdong.app.mall"
    val ecommercePackages = setOf(MEITUAN, TAOBAO, PINDUODUO, JD)
    val entries = listOf(
        PaymentApp(WECHAT, "微信"),
        PaymentApp(ALIPAY, "支付宝"),
        PaymentApp(MEITUAN, "美团"),
        PaymentApp(TAOBAO, "淘宝"),
        PaymentApp(PINDUODUO, "拼多多"),
        PaymentApp(JD, "京东"),
    )
    val supportedPackages by lazy { entries.map { it.packageName }.toSet() }
    fun supported(packageName: String) = packageName in supportedPackages
}
