package com.shangkeschedule.data.sync

/**
 * 川工课表同步 · 课程差异比较（纯逻辑，无平台依赖，可在 host 侧单测）。
 *
 * 同步流程（手动刷新与后台检查共用）：
 * 1. 在 WebView 登录态内重新执行本校适配脚本，拿到最新课程快照；
 * 2. 用 [CourseSyncDiff.diff] 与上次落库快照对比，得到新增/删除/修改清单；
 * 3. 只把变化写入本地课表（复用 CourseConversionRepository 的替换逻辑，
 *    该逻辑失败时保留原课表，绝不因同步失败清空）；
 * 4. 把 [CourseChange] 清单作为「课程变化通知/历史记录」展示给用户。
 *
 * 身份判据：同一门课以 (课程名, 星期, 开始节次, 结束节次) 为身份；
 * 教师/地点/周次不同视为「修改」，身份本身不同视为删除+新增。
 * 注意：同课同节次但分段周次不同（如第3周毓秀105A、第4周 C303）是 kbList 的
 * 两条独立记录——它们的周次不同，按修改处理会把两条并成一条，故 weeks 也进
 * 身份判据的弱匹配：先按强身份分组，组内再按 weeks 精确配对，配不上才报修改。
 */
data class SyncedCourse(
    val name: String,
    val teacher: String,
    val position: String,
    val day: Int,
    val startSection: Int?,
    val endSection: Int?,
    val weeks: List<Int>,
    val remark: String? = null
) {
    val identity: String get() = "$name|$day|$startSection|$endSection"
    val weeksKey: String get() = weeks.sorted().joinToString(",")
}

enum class CourseChangeType { ADDED, REMOVED, MODIFIED }

data class CourseChange(
    val type: CourseChangeType,
    val courseName: String,
    /** 变化字段：教师 / 地点 / 周次 / 时间；新增与删除时为空。 */
    val field: String? = null,
    val oldValue: String? = null,
    val newValue: String? = null,
    /** 原安排与新安排的人类可读描述（通知正文用）。 */
    val oldSchedule: String,
    val newSchedule: String,
    /** 信息来源，如「正方教务个人课表」「教务处公告（待确认）」。 */
    val source: String,
    /** 获取时间（epoch 毫秒），由调用方传入，避免纯逻辑里取系统时间。 */
    val detectedAtEpochMs: Long
)

object CourseSyncDiff {

    fun describe(c: SyncedCourse): String {
        val dayText = "周${"一二三四五六日".getOrNull(c.day - 1) ?: c.day}"
        val sectionText = if (c.startSection != null && c.endSection != null) "第${c.startSection}-${c.endSection}节" else "时间未定"
        val weeksText = if (c.weeks.isEmpty()) "周次未知" else "第${c.weeks.sorted().joinToString(",")}周"
        return "$dayText $sectionText · ${c.position.ifBlank { "地点未定" }} · $weeksText · ${c.teacher.ifBlank { "教师未安排" }}"
    }

    fun diff(
        old: List<SyncedCourse>,
        new: List<SyncedCourse>,
        source: String,
        detectedAtEpochMs: Long
    ): List<CourseChange> {
        val changes = mutableListOf<CourseChange>()
        val oldByIdentity = old.groupBy { it.identity }
        val newByIdentity = new.groupBy { it.identity }

        for ((identity, newGroup) in newByIdentity) {
            val oldGroup = oldByIdentity[identity]
            if (oldGroup == null) {
                newGroup.forEach {
                    changes += CourseChange(
                        type = CourseChangeType.ADDED, courseName = it.name,
                        oldSchedule = "（无）", newSchedule = describe(it),
                        source = source, detectedAtEpochMs = detectedAtEpochMs
                    )
                }
                continue
            }
            // 组内按 weeks 精确配对，剩下的再两两比对字段（处理分段多教室）。
            val unmatchedOld = oldGroup.toMutableList()
            val unmatchedNew = mutableListOf<SyncedCourse>()
            for (candidate in newGroup) {
                val exact = unmatchedOld.firstOrNull {
                    it.weeksKey == candidate.weeksKey && it.teacher == candidate.teacher && it.position == candidate.position
                }
                if (exact != null) unmatchedOld.remove(exact) else unmatchedNew += candidate
            }
            val pairCount = minOf(unmatchedOld.size, unmatchedNew.size)
            for (i in 0 until pairCount) {
                val o = unmatchedOld[i]; val n = unmatchedNew[i]
                if (o.teacher != n.teacher) changes += modified(o, n, "教师", o.teacher, n.teacher, source, detectedAtEpochMs)
                if (o.position != n.position) changes += modified(o, n, "地点", o.position, n.position, source, detectedAtEpochMs)
                if (o.weeksKey != n.weeksKey) changes += modified(
                    o, n, "周次",
                    "第${o.weeks.sorted().joinToString(",")}周", "第${n.weeks.sorted().joinToString(",")}周",
                    source, detectedAtEpochMs
                )
            }
            for (i in pairCount until unmatchedNew.size) {
                val n = unmatchedNew[i]
                changes += CourseChange(
                    type = CourseChangeType.ADDED, courseName = n.name,
                    oldSchedule = "（无）", newSchedule = describe(n),
                    source = source, detectedAtEpochMs = detectedAtEpochMs
                )
            }
            for (i in pairCount until unmatchedOld.size) {
                val o = unmatchedOld[i]
                changes += CourseChange(
                    type = CourseChangeType.REMOVED, courseName = o.name,
                    oldSchedule = describe(o), newSchedule = "（无）",
                    source = source, detectedAtEpochMs = detectedAtEpochMs
                )
            }
        }
        for ((identity, oldGroup) in oldByIdentity) {
            if (identity !in newByIdentity) {
                oldGroup.forEach {
                    changes += CourseChange(
                        type = CourseChangeType.REMOVED, courseName = it.name,
                        oldSchedule = describe(it), newSchedule = "（无）",
                        source = source, detectedAtEpochMs = detectedAtEpochMs
                    )
                }
            }
        }
        return changes
    }

    private fun modified(
        o: SyncedCourse, n: SyncedCourse, field: String,
        oldValue: String, newValue: String, source: String, at: Long
    ) = CourseChange(
        type = CourseChangeType.MODIFIED, courseName = n.name, field = field,
        oldValue = oldValue, newValue = newValue,
        oldSchedule = describe(o), newSchedule = describe(n),
        source = source, detectedAtEpochMs = at
    )
}

/** 同步状态（手动刷新/后台检查共用同一份状态记录，供 UI 显示最后同步时间与结果）。 */
data class ScheduleSyncStatus(
    val lastAttemptEpochMs: Long? = null,
    val lastSuccessEpochMs: Long? = null,
    val state: State = State.NEVER,
    val message: String = "",
    val lastChangeCount: Int = 0
) {
    enum class State { NEVER, RUNNING, SUCCESS, FAILED, LOGIN_EXPIRED }

    fun onStarted(nowEpochMs: Long): ScheduleSyncStatus =
        copy(lastAttemptEpochMs = nowEpochMs, state = State.RUNNING, message = "正在同步")

    /** 成功才更新课表与 lastSuccess；失败保留本地课表与上次成功时间，只记录失败原因。 */
    fun onSucceeded(nowEpochMs: Long, changeCount: Int): ScheduleSyncStatus =
        copy(
            lastAttemptEpochMs = nowEpochMs, lastSuccessEpochMs = nowEpochMs,
            state = State.SUCCESS, message = "同步成功", lastChangeCount = changeCount
        )

    fun onFailed(nowEpochMs: Long, reason: String, loginExpired: Boolean = false): ScheduleSyncStatus =
        copy(
            lastAttemptEpochMs = nowEpochMs,
            state = if (loginExpired) State.LOGIN_EXPIRED else State.FAILED,
            message = if (loginExpired) "登录已过期，请重新登录教务系统后再同步" else reason
        )
}
