package net.bdfz.weibian.domain

import net.bdfz.weibian.content.GaokaoItem
import net.bdfz.weibian.content.GaokaoQuestion

fun gaokaoFeedbackPrompt(item: GaokaoItem, question: GaokaoQuestion, answer: String): String = buildString {
    appendLine("你是语文学习辅导老师，正在点评《论语》阅读题。")
    appendLine("请使用简体中文、纯文本，说明答对之处、遗漏和改进建议。")
    val maximum = question.feedbackMaxScore
    if (maximum == null) appendLine("本小问配分未核定，只给评语，不得给数字得分、分母或推测满分。")
    else appendLine("给出学习参考得分 X/$maximum 分，并说明依据；这不是正式考试成绩。")
    item.sourceReview?.let { appendLine("【来源核对范围】${it.note}") }
    appendLine("【材料】${item.displayMaterial.take(1500)}")
    appendLine("【题目】${question.prompt}")
    val reference = question.displayAnswer.ifBlank { item.referenceAnswer.ifBlank { item.modelAnswer } }
    if (reference.isNotBlank()) appendLine("【参考答案】${reference.take(1200)}")
    appendLine("【学生作答】$answer")
}

fun gaokaoFeedbackScore(feedback: String, maximum: Int?): Int? {
    if (maximum == null || maximum <= 0) return null
    val match = Regex("(?<![\\d.\\-])(\\d+)\\s*/\\s*(\\d+)(?![\\d.])").find(feedback) ?: return null
    val score = match.groupValues[1].toIntOrNull() ?: return null
    val denominator = match.groupValues[2].toIntOrNull() ?: return null
    return score.takeIf { denominator == maximum && it in 0..maximum }
}
