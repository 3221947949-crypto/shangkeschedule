package com.shangkeschedule.data.sync

/**
 * 川工教务处公告解析（纯逻辑，无网络、无平台依赖）。
 *
 * 数据源（2026-11-06 基于学校官网实际结构独立核查）：
 * - 教务处首页 https://www.scit.edu.cn/jwc/home ：服务器直出 HTML，
 *   含「通知公告」栏目与标题/日期，栏目列表页为 /jwc/newslistli919.htm；
 * - 公告详情页 URL 规律：/jwc/newslistli919_<数字>.htm，正文含
 *   「发布时间：yyyy-MM-dd 来源：教务处」与适用对象描述。
 * - 本沙箱云端直连该站返回空回复（服务器对云端连接不响应），故抓取必须在
 *   用户设备/校园网环境执行；本类只负责把抓到的 HTML 解析成结构化公告。
 *
 * 适用范围纪律：公告可能只适用于某学院/年级/班级。仅凭标题与正文无法可靠
 * 判断是否适用于当前用户时，一律标记 needsConfirmation=true，只作
 * 「待确认通知」展示，绝不直接改动个人课表。
 */
enum class ScitNoticeCategory { ADJUST, SUSPEND, MAKEUP, HOLIDAY_SHIFT, OTHER }

data class ScitNotice(
    val title: String,
    val url: String,
    val dateText: String?,
    val category: ScitNoticeCategory,
    /** 从标题/正文中提取到的适用范围线索（如学院、年级、班级名），提取不到为 null。 */
    val scopeHint: String?,
    /** 无法自动确认适用范围时恒为 true：只展示、不改课表。 */
    val needsConfirmation: Boolean = true,
    val source: String = "四川工业科技学院教务处官网",
    val fetchedAtEpochMs: Long = 0L
)

object ScitNoticeParser {

    const val JWC_HOME = "https://www.scit.edu.cn/jwc/home"
    const val NOTICE_LIST_PATH = "/jwc/newslistli919.htm"

    private val anchorRegex = Regex(
        """<a[^>]+href\s*=\s*["']([^"']*newslistli919_\d+\.htm)["'][^>]*>(.*?)</a>""",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
    )
    private val dateRegex = Regex("""(20\d{2})[-/.](\d{1,2})[-/.](\d{1,2})""")
    private val tagRegex = Regex("""<[^>]+>""")
    private val scopeRegex = Regex("""(20\d{2}级|20\d{2}届|[\u4e00-\u9fa5]{2,10}学院|[\u4e00-\u9fa5]{2,8}班)""")

    fun classify(title: String, body: String = ""): ScitNoticeCategory {
        val text = title + body
        return when {
            // 顺序即优先级：调休通知常同时出现「放假/调休/补课」字样，先认调休。
            text.contains("调休") || text.contains("放假安排") -> ScitNoticeCategory.HOLIDAY_SHIFT
            text.contains("补课") -> ScitNoticeCategory.MAKEUP
            text.contains("停课") -> ScitNoticeCategory.SUSPEND
            text.contains("调课") || text.contains("调整") && text.contains("课程") -> ScitNoticeCategory.ADJUST
            else -> ScitNoticeCategory.OTHER
        }
    }

    fun extractScopeHint(text: String): String? =
        scopeRegex.find(text)?.groupValues?.get(1)

    /** 解析通知公告列表页/首页 HTML，抽取公告条目（按 URL 去重）。 */
    fun parseNoticeListHtml(html: String, baseUrl: String, fetchedAtEpochMs: Long): List<ScitNotice> {
        val seen = mutableSetOf<String>()
        val result = mutableListOf<ScitNotice>()
        for (match in anchorRegex.findAll(html)) {
            val href = match.groupValues[1].trim()
            val rawTitle = match.groupValues[2].replace(tagRegex, "").trim()
            if (rawTitle.isEmpty()) continue
            val url = when {
                href.startsWith("http") -> href
                href.startsWith("/") -> {
                    val origin = Regex("""^https?://[^/]+""").find(baseUrl)?.value ?: "https://www.scit.edu.cn"
                    origin + href
                }
                else -> baseUrl.trimEnd('/') + "/" + href
            }
            if (!seen.add(url)) continue
            // 日期通常紧跟在链接之后的一小段文本里，取链接后 120 字符内的第一个日期。
            val tail = html.substring(match.range.last + 1, minOf(html.length, match.range.last + 121))
            val date = dateRegex.find(tail)?.value
            result += ScitNotice(
                title = rawTitle,
                url = url,
                dateText = date,
                category = classify(rawTitle),
                scopeHint = extractScopeHint(rawTitle),
                needsConfirmation = true,
                fetchedAtEpochMs = fetchedAtEpochMs
            )
        }
        return result
    }

    /** 只保留与教学安排相关的公告（调课/停课/补课/调休），其余栏目公告不打扰用户。 */
    fun teachingNotices(notices: List<ScitNotice>): List<ScitNotice> =
        notices.filter { it.category != ScitNoticeCategory.OTHER }
}
