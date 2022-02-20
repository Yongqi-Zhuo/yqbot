package top.saucecode

import java.util.Date
import java.util.concurrent.TimeUnit

object TimeAgo {
    private val times = listOf<Long>(
        TimeUnit.DAYS.toMillis(365),
        TimeUnit.DAYS.toMillis(30),
        TimeUnit.DAYS.toMillis(1),
        TimeUnit.HOURS.toMillis(1),
        TimeUnit.MINUTES.toMillis(1),
        TimeUnit.SECONDS.toMillis(1)
    )
    private val timesString = listOf<String>("年", "个月", "天", "小时", "分钟", "秒")
    fun fromTimeStamp(ts: Long): String {
        val duration = Date().time - ts
        if (duration < 0) return "未来"
        for (i in 0 until times.size) {
            val current = times[i]
            val temp = duration / current
            if (temp > 0) return temp.toString() + timesString[i] + "前"
        }
        return "0秒前"
    }
}