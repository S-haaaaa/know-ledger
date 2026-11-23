package net.ankio.ledger.core

import org.junit.Assert.*
import org.junit.Test

class AccessibilityBillFallbackTest {
    private val wechat = "com.tencent.mm.framework.app.UIPageFragmentActivity"
    private val alipay = "com.alipay.android.app.flybird.ui.window.FlyBirdWindowActivity"

    @Test fun parsesWechatFastExitPaymentWithMerchantAndCategory() {
        val page = PaymentPage(PaymentApps.WECHAT, 7, wechat, listOf("支付成功", "星巴克咖啡", "¥35.00", "完成"))
        val match = requireNotNull(PaymentPageDetector.detect(page))
        val bill = requireNotNull(AccessibilityBillFallback.parse(match, 1_760_000_000_000L))
        assertEquals(3500L, bill.amountCents)
        assertEquals(Direction.EXPENSE, bill.direction)
        assertEquals("星巴克咖啡", bill.merchant)
        assertEquals("餐饮美食", bill.category)
        assertTrue(bill.reliable)
        assertEquals(AccessibilityBillFallback.REASON, bill.reason)
    }

    @Test fun parsesAlipayTransferRecipientAndBareAmount() {
        val transfer = requireNotNull(PaymentPageDetector.detect(PaymentPage(PaymentApps.ALIPAY, 9, alipay, listOf("转账成功", "-123.45", "给测试收款人"))))
        val transferBill = requireNotNull(AccessibilityBillFallback.parse(transfer, 1_760_000_000_000L))
        assertEquals(12345L, transferBill.amountCents)
        assertEquals("测试收款人", transferBill.merchant)

        val bare = requireNotNull(PaymentPageDetector.detect(PaymentPage(PaymentApps.WECHAT, 9, wechat, listOf("支付成功", "滴滴出行", "18.50", "完成"))))
        val bareBill = requireNotNull(AccessibilityBillFallback.parse(bare, 1_760_000_000_000L))
        assertEquals(1850L, bareBill.amountCents)
        assertEquals("滴滴出行", bareBill.merchant)
        assertEquals("交通出行", bareBill.category)
    }

    @Test fun ignoresPromotionalLinesAndPreservesTransactionDetails() {
        val page = PaymentPage(
            PaymentApps.WECHAT,
            11,
            wechat,
            listOf(
                "支付成功",
                "瑞幸咖啡",
                "¥19.90",
                "立减优惠5.00元",
                "领取优惠券",
                "付款时间",
                "2026年10月17日 14:25:00",
                "交易单号",
                "4200002400202610179988776655",
                "返回商家",
            ),
        )
        val match = requireNotNull(PaymentPageDetector.detect(page))
        val bill = requireNotNull(AccessibilityBillFallback.parse(match, 1_792_247_100_000L))
        assertEquals(1990L, bill.amountCents)
        assertEquals("瑞幸咖啡", bill.merchant)
        assertEquals("4200002400202610179988776655", bill.transactionId)
        assertTrue(bill.timeExplicit)
    }

    @Test fun rejectsSensitiveOrUnsupportedOrZeroMatches() {
        val valid = requireNotNull(PaymentPageDetector.detect(PaymentPage(PaymentApps.WECHAT, 1, wechat, listOf("支付成功", "¥12.00", "完成"))))
        assertNull(AccessibilityBillFallback.parse(valid.copy(texts = listOf("支付成功", "¥12.00", "请输入支付密码")), 1_760_000_000_000L))
        assertNull(AccessibilityBillFallback.parse(valid.copy(amountCents = 0L), 1_760_000_000_000L))
        assertNull(AccessibilityBillFallback.parse(valid.copy(packageName = "com.example.unknown"), 1_760_000_000_000L))
    }
}
