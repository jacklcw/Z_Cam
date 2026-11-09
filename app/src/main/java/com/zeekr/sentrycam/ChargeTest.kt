package com.zeekr.sentrycam

import android.content.Context

/** Test only: writes candidate values to ICharging.CHARGE_FUNC_ADJUST_MAX_CURRENT (0x24100b00), logs before/after each, then restores the original. */
object ChargeTest {
    private const val ID = 0x24100b00
    private val EXTRA = intArrayOf(0x24100600, 0x24100700, 0x24100800, 0x24100900, 0x24130200)

    fun run(ctx: Context) {
        fun log(m: String) = Logger.log(ctx, "chargetest: $m")
        try {
            val car = BlindSpotHud.carCache ?: Class.forName("com.ecarx.xui.adaptapi.car.Car").getMethod("create", Context::class.java).invoke(null, ctx.applicationContext)
            val fn = car.javaClass.getMethod("getICarFunction").invoke(car)
            val get = fn.javaClass.getMethod("getFunctionValue", Int::class.javaPrimitiveType)
            val set = fn.javaClass.getMethod("setFunctionValue", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
            fun rd(id: Int): String = try { get.invoke(fn, id).toString() } catch (e: Throwable) { "err:" + (e.cause ?: e).javaClass.simpleName }
            fun snap() = EXTRA.joinToString(" ") { "0x" + Integer.toHexString(it) + "=" + rd(it) }
            val orig = rd(ID)
            log("start orig=$orig " + snap())
            for (v in intArrayOf(6, 8, 10, 16, 32, 1, 2)) {
                val r = try { set.invoke(fn, ID, v) } catch (e: Throwable) { "err:" + (e.cause ?: e).javaClass.simpleName + " " + (e.cause ?: e).message }
                Thread.sleep(1500)
                log("set $v returned $r now ${rd(ID)} " + snap())
            }
            orig.toIntOrNull()?.let {
                val r = try { set.invoke(fn, ID, it) } catch (e: Throwable) { "err" }
                Thread.sleep(1500)
                log("restore $it returned $r now ${rd(ID)}")
            }
            log("done")
        } catch (e: Throwable) { log("failed ${(e.cause ?: e).javaClass.simpleName} ${(e.cause ?: e).message}") }
    }
}
