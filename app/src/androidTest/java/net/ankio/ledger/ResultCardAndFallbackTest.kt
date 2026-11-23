package net.ankio.ledger

import android.content.Context
import android.content.ContextWrapper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import net.ankio.ledger.capture.ChannelSettings
import net.ankio.ledger.capture.RecognitionBanner
import net.ankio.ledger.core.Capture
import net.ankio.ledger.core.Direction
import net.ankio.ledger.core.ParsedBill
import net.ankio.ledger.core.Source
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ResultCardAndFallbackTest {
    @Test fun channelSettingsPersistTextFallbackAndResultCardDuration() {
        val app = ApplicationProvider.getApplicationContext<LedgerApp>()
        val originalFallback = app.channels.textFallbackEnabled()
        val originalDuration = app.channels.resultCardDurationMs()
        try {
            app.channels.setTextFallbackEnabled(false)
            assertFalse(app.channels.textFallbackEnabled())
            app.channels.setTextFallbackEnabled(true)
            assertTrue(app.channels.textFallbackEnabled())

            for (ms in listOf(3_000L, 5_000L, 8_000L)) {
                app.channels.setResultCardDurationMs(ms)
                assertEquals(ms, app.channels.resultCardDurationMs())
            }
        } finally {
            app.channels.setTextFallbackEnabled(originalFallback)
            app.channels.setResultCardDurationMs(originalDuration)
        }
    }

    @Test fun resultCardSupportsCustomDurationAndSwipeUpDismiss() {
        val app = ApplicationProvider.getApplicationContext<LedgerApp>()
        val originalDuration = app.channels.resultCardDurationMs()
        var overlay: View? = null
        val systemWindows = app.getSystemService(WindowManager::class.java)
        val manager = object : WindowManager by systemWindows {
            override fun getMaximumWindowMetrics() = systemWindows.maximumWindowMetrics
            override fun getCurrentWindowMetrics() = systemWindows.currentWindowMetrics
            override fun addView(view: View, params: ViewGroup.LayoutParams) { overlay = view }
            override fun removeViewImmediate(view: View) { if (overlay === view) overlay = null }
        }
        val context = object : ContextWrapper(app) {
            override fun getSystemService(name: String): Any? =
                if (name == Context.WINDOW_SERVICE) manager else super.getSystemService(name)
        }
        val banner = RecognitionBanner(context)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val saved = app.store.ingest(
            Capture(Source.OCR, "payment_auto:com.tencent.mm", "支付成功\n测试商户\n¥18.80"),
            ParsedBill(1880L, Direction.EXPENSE, "测试商户", "餐饮美食", System.currentTimeMillis(), null, true, "test"),
        ) as net.ankio.ledger.data.IngestResult.Saved
        try {
            app.channels.setResultCardDurationMs(8_000L)
            banner.show("已记账 ¥18.80", recordId = saved.record.id)
            val until = SystemClock.elapsedRealtime() + 2000
            while (overlay == null && SystemClock.elapsedRealtime() < until) SystemClock.sleep(10)
            assertNotNull(overlay)

            val deadline = RecognitionBanner::class.java.getDeclaredField("resultDeadline").apply { isAccessible = true }.getLong(banner)
            assertTrue(deadline - SystemClock.elapsedRealtime() > 6_000L)

            val card = RecognitionBanner::class.java.getDeclaredField("cardView").apply { isAccessible = true }.get(banner) as View
            instrumentation.runOnMainSync {
                val now = SystemClock.uptimeMillis()
                val x = (card.left + card.right) / 2f
                val startY = (card.top + card.bottom) / 2f
                overlay!!.dispatchTouchEvent(MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, x, startY, 0))
                overlay!!.dispatchTouchEvent(MotionEvent.obtain(now, now + 20, MotionEvent.ACTION_MOVE, x, startY - 220f, 0))
                overlay!!.dispatchTouchEvent(MotionEvent.obtain(now, now + 40, MotionEvent.ACTION_UP, x, startY - 260f, 0))
            }
            val closeUntil = SystemClock.elapsedRealtime() + 1000
            while (overlay != null && SystemClock.elapsedRealtime() < closeUntil) SystemClock.sleep(10)
            assertNull(overlay)
        } finally {
            instrumentation.runOnMainSync { banner.removeNow() }
            app.store.delete(saved.record.id)
            app.channels.setResultCardDurationMs(originalDuration)
        }
    }
}
