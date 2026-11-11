import com.shangkeschedule.data.sync.CourseChangeType
import com.shangkeschedule.data.sync.CourseSyncDiff
import com.shangkeschedule.data.sync.ScheduleSyncStatus
import com.shangkeschedule.data.sync.ScitNoticeCategory
import com.shangkeschedule.data.sync.ScitNoticeParser
import com.shangkeschedule.data.sync.SyncedCourse
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 川工课表同步纯逻辑回归：差异比较 + 同步状态 + 教务处公告解析。
 * 课程样本取自用户本人正方课表截图整理的字段模式（其本人数据），非接口抓包。
 */
class ScitSyncLogicTest {

    private fun course(
        name: String = "模拟电子技术", teacher: String = "汪美", position: String = "D505",
        day: Int = 5, start: Int = 5, end: Int = 6, weeks: List<Int> = (2..18).toList()
    ) = SyncedCourse(name, teacher, position, day, start, end, weeks)

    @Test
    fun diffDetectsAddedRemovedAndModified() {
        val old = listOf(course(), course(name = "毛概", day = 5, start = 7, end = 8))
        val new = listOf(
            course(position = "C303", weeks = (2..16).toList()),
            course(name = "操作系统", teacher = "裴子春", day = 5, start = 3, end = 4)
        )
        val changes = CourseSyncDiff.diff(old, new, source = "正方教务个人课表", detectedAtEpochMs = 1000L)
        assertTrue(changes.any { it.type == CourseChangeType.ADDED && it.courseName == "操作系统" })
        assertTrue(changes.any { it.type == CourseChangeType.REMOVED && it.courseName == "毛概" })
        assertTrue(changes.any { it.type == CourseChangeType.MODIFIED && it.field == "地点" && it.newValue == "C303" })
        assertTrue(changes.any { it.type == CourseChangeType.MODIFIED && it.field == "周次" })
        assertTrue(changes.all { it.source == "正方教务个人课表" && it.detectedAtEpochMs == 1000L })
    }

    @Test
    fun diffKeepsSegmentedRoomsSeparate() {
        // 同课同节次分段多教室（第3周毓秀105A / 第4周 C303）不得被并成一条或误报变化。
        val segmented = listOf(
            course(day = 3, start = 7, end = 8, position = "毓秀105A", weeks = listOf(3)),
            course(day = 3, start = 7, end = 8, position = "C303", weeks = listOf(4))
        )
        assertEquals(emptyList(), CourseSyncDiff.diff(segmented, segmented, "正方教务个人课表", 0L))
    }

    @Test
    fun syncStatusFailureKeepsLastSuccess() {
        val ok = ScheduleSyncStatus().onSucceeded(nowEpochMs = 100L, changeCount = 2)
        val failed = ok.onFailed(nowEpochMs = 200L, reason = "网络异常")
        assertEquals(100L, failed.lastSuccessEpochMs)
        assertEquals(ScheduleSyncStatus.State.FAILED, failed.state)
        val expired = ok.onFailed(nowEpochMs = 300L, reason = "", loginExpired = true)
        assertEquals(ScheduleSyncStatus.State.LOGIN_EXPIRED, expired.state)
        assertTrue(expired.message.contains("重新登录"))
    }

    @Test
    fun noticeParserClassifiesAndExtracts() {
        val html = """
            <ul>
              <li><a href="newslistli919_80001.htm">关于国庆节放假调休及补课安排的通知</a><span>2026-09-25</span></li>
              <li><a href="/jwc/newslistli919_80002.htm">关于2025级人工智能专业停课的通知</a><span>2026-09-26</span></li>
              <li><a href="newslistli919_80003.htm">教务处召开工作会议</a><span>2026-09-27</span></li>
            </ul>
        """.trimIndent()
        val notices = ScitNoticeParser.parseNoticeListHtml(html, ScitNoticeParser.JWC_HOME, fetchedAtEpochMs = 42L)
        assertEquals(3, notices.size)
        assertEquals(ScitNoticeCategory.HOLIDAY_SHIFT, notices[0].category)
        assertEquals("2026-09-25", notices[0].dateText)
        assertEquals(ScitNoticeCategory.SUSPEND, notices[1].category)
        assertEquals("2025级", notices[1].scopeHint)
        assertTrue(notices.all { it.needsConfirmation }, "公告一律待确认，不得直接改课表")
        assertEquals(2, ScitNoticeParser.teachingNotices(notices).size)
    }
}
