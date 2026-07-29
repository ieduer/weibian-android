package net.bdfz.weibian.domain

import net.bdfz.weibian.content.BankItem
import net.bdfz.weibian.content.Chapter
import net.bdfz.weibian.content.ContentBundle
import kotlin.random.Random

/**
 * 学习引擎 —— 把「一章《论语》」变成若干道可作答的任务。
 *
 * 任务分两种来源：
 *  · 人工精编题（215 道，八种题型）—— 覆盖注释掌握与综合理解，
 *    每个错项都带 why 诊断，是题库的质量基线；
 *  · 由正文与注释确定性生成的题 —— 覆盖原文掌握（识文、补字、连句、释词）。
 *    生成题只从既有语料派生，不凭空编造内容。
 *
 * 生成用固定种子（章 id + 题型 + 轮次），因此同一章的题目稳定可复现，
 * 不会每次进来都换一套让人无从复习；换轮次才会换题。
 */

enum class TaskKind(val label: String, val hint: String) {
    RECOGNIZE("识文", "认出这句话出自哪一章"),
    CLOZE("补字", "补出缺失的字"),
    ORDER("连句", "把打乱的句子排回原序"),
    GLOSS("释词", "解释加注的词"),
    MEANING("通义", "理解整章的意思"),
    CONCEPT("辨概念", "分辨核心概念"),
    LINKAGE("贯通", "关联不同章句"),
    APPLY("致用", "用到实际情境"),
    MISREAD("辨惑", "识破似是而非的读法"),
}

data class TaskOption(
    val id: String,
    val text: String,
    /** 为什么这个选项不对；正确项为空 */
    val why: String = "",
)

data class LearningTask(
    val id: String,
    val kind: TaskKind,
    val chapterId: Int,
    /** 题干 */
    val stem: String,
    /** 题干上方展示的材料（原文片段、情境等），可为空 */
    val context: String,
    val options: List<TaskOption>,
    val answerId: String,
    val explanation: String,
    val difficulty: Int,
    /** 来源：authored = 人工精编；derived = 由语料生成 */
    val origin: String,
) {
    fun isCorrect(optionId: String): Boolean = optionId == answerId
    fun option(optionId: String): TaskOption? = options.firstOrNull { it.id == optionId }
}

class LearningEngine(private val bundle: ContentBundle) {

    private val optionIds = listOf("a", "b", "c", "d")

    /**
     * 为一章生成一组任务。
     *
     * @param round 轮次；复习时递增，题目随之换一批而不是永远重复同几道。
     */
    fun tasksFor(chapterId: Int, round: Int = 0, limit: Int = 8): List<LearningTask> {
        val chapter = bundle.chapter(chapterId) ?: return emptyList()
        val tasks = mutableListOf<LearningTask>()

        // 人工精编题优先：质量最高，且覆盖生成题做不到的推理层级。
        bundle.questionsFor(chapterId).forEach { tasks += it.toTask() }

        // 生成题补足原文掌握维度。
        buildCloze(chapter, round)?.let { tasks += it }
        buildGloss(chapter, round)?.let { tasks += it }
        buildRecognize(chapter, round)?.let { tasks += it }
        buildOrder(chapter, round)?.let { tasks += it }

        // 稳定打乱：同一章同一轮次顺序固定，换轮次才变。
        return tasks.shuffled(Random(chapterId * 31 + round)).take(limit)
    }

    /**
     * 依据薄弱环节编排的练习序列。
     * 先攻难点章，再补没练过的章，最后穿插已掌握章做保持性复习。
     */
    fun adaptiveQueue(
        mastery: Map<Int, ChapterMastery>,
        round: Int = 0,
        size: Int = 12,
    ): List<LearningTask> {
        val struggling = mastery.values.filter { it.struggling }.map { it.chapterId }
        val unpracticed = bundle.chapters
            .filter { chapter ->
                val record = mastery[chapter.id]
                record == null || record.attempts == 0
            }
            .map { it.id }
        val maintaining = mastery.values
            .filter { it.mastered }
            .sortedBy { it.reviews }
            .map { it.chapterId }

        val ordered = (struggling + unpracticed.take(size) + maintaining).distinct()
        val out = mutableListOf<LearningTask>()
        for (chapterId in ordered) {
            if (out.size >= size) break
            out += tasksFor(chapterId, round, limit = 2)
        }
        return out.take(size)
    }

    // -----------------------------------------------------------------------
    // 人工精编题 → 任务
    // -----------------------------------------------------------------------

    private fun BankItem.toTask(): LearningTask = LearningTask(
        id = id,
        kind = when (type) {
            "comprehension" -> TaskKind.MEANING
            "interpretation" -> TaskKind.MEANING
            "concept" -> TaskKind.CONCEPT
            "linkage" -> TaskKind.LINKAGE
            "conflict" -> TaskKind.LINKAGE
            "figure" -> TaskKind.MEANING
            "application" -> TaskKind.APPLY
            "misread" -> TaskKind.MISREAD
            else -> TaskKind.MEANING
        },
        chapterId = primaryRef,
        stem = stem,
        context = context,
        options = options.map { TaskOption(it.id, it.text, it.why) },
        answerId = answerId,
        explanation = explanation,
        difficulty = difficulty,
        origin = "authored",
    )

    // -----------------------------------------------------------------------
    // 生成题
    // -----------------------------------------------------------------------

    /**
     * 补字：挖掉正文中的一个实词，从别章取同长度片段作干扰。
     * 只在正文足够长时出题，短章挖字会变成猜谜。
     */
    private fun buildCloze(chapter: Chapter, round: Int): LearningTask? {
        val text = chapter.plainOriginal
        if (text.length < 12) return null
        val rng = Random(chapter.id * 977 + round)

        // 避开引号书名号等，挖一个 2 字窗口
        val candidates = (1 until text.length - 2).filter { i ->
            (0 until 2).all { text[i + it].isChineseChar() }
        }
        if (candidates.isEmpty()) return null
        val start = candidates[rng.nextInt(candidates.size)]
        val answer = text.substring(start, start + 2)
        val masked = text.replaceRange(start, start + 2, "▢▢")

        val distractors = bundle.chapters
            .asSequence()
            .filter { it.id != chapter.id && it.plainOriginal.length > 6 }
            .map { other ->
                val o = other.plainOriginal
                val at = rng.nextInt(0, (o.length - 2).coerceAtLeast(1))
                o.substring(at, (at + 2).coerceAtMost(o.length))
            }
            .filter { it.length == 2 && it != answer && it.all { c -> c.isChineseChar() } }
            .distinct()
            .take(3)
            .toList()
        if (distractors.size < 3) return null

        val texts = (listOf(answer) + distractors).shuffled(rng)
        val answerIndex = texts.indexOf(answer)
        return LearningTask(
            id = "cloze-${chapter.id}-$round",
            kind = TaskKind.CLOZE,
            chapterId = chapter.id,
            stem = "补出「▢▢」处缺失的字：",
            context = masked,
            options = texts.mapIndexed { i, t -> TaskOption(optionIds[i], t) },
            answerId = optionIds[answerIndex],
            explanation = "本句出自《论语·${chapter.bookName}》${chapter.ref}：${chapter.plainOriginal}",
            difficulty = 1,
            origin = "derived",
        )
    }

    /**
     * 释词：用杨伯峻的注释直接出题，干扰项取自别章注释。
     * 这是「注释掌握」最直接的检验方式。
     */
    private fun buildGloss(chapter: Chapter, round: Int): LearningTask? {
        val usable = chapter.annotations.filter { it.term.isNotBlank() && it.gloss.length in 4..60 }
        if (usable.isEmpty()) return null
        val rng = Random(chapter.id * 613 + round)
        val target = usable[rng.nextInt(usable.size)]

        val distractors = bundle.chapters
            .asSequence()
            .filter { it.id != chapter.id }
            .flatMap { it.annotations.asSequence() }
            .filter { it.gloss.length in 4..60 && it.term != target.term }
            .map { it.gloss.trimEnd('。') }
            .distinct()
            .shuffled(rng)
            .take(3)
            .toList()
        if (distractors.size < 3) return null

        val answerText = target.gloss.trimEnd('。')
        val texts = (listOf(answerText) + distractors).shuffled(rng)
        return LearningTask(
            id = "gloss-${chapter.id}-${target.number}-$round",
            kind = TaskKind.GLOSS,
            chapterId = chapter.id,
            stem = "《论语·${chapter.bookName}》${chapter.ref}中，「${target.term}」作何解？",
            context = chapter.plainOriginal,
            options = texts.mapIndexed { i, t -> TaskOption(optionIds[i], t) },
            answerId = optionIds[texts.indexOf(answerText)],
            explanation = "杨伯峻《论语译注》注：${target.term}——${target.gloss}",
            difficulty = 2,
            origin = "derived",
        )
    }

    /** 识文：给译文，选出对应的原文。检验原文与文意的对应关系。 */
    private fun buildRecognize(chapter: Chapter, round: Int): LearningTask? {
        if (chapter.translation.length < 10 || chapter.plainOriginal.length < 6) return null
        val rng = Random(chapter.id * 449 + round)
        val distractors = bundle.chapters
            .asSequence()
            .filter { it.id != chapter.id && it.plainOriginal.length in 6..40 }
            .map { it.plainOriginal }
            .distinct()
            .shuffled(rng)
            .take(3)
            .toList()
        if (distractors.size < 3) return null

        val answerText = chapter.plainOriginal
        val texts = (listOf(answerText) + distractors).shuffled(rng)
        return LearningTask(
            id = "recog-${chapter.id}-$round",
            kind = TaskKind.RECOGNIZE,
            chapterId = chapter.id,
            stem = "下面这段译文对应哪一句原文？",
            context = chapter.translation,
            options = texts.mapIndexed { i, t -> TaskOption(optionIds[i], t) },
            answerId = optionIds[texts.indexOf(answerText)],
            explanation = "出自《论语·${chapter.bookName}》${chapter.ref}。",
            difficulty = 1,
            origin = "derived",
        )
    }

    /**
     * 连句：把整章切成小句打乱，选出正确顺序。
     * 只有分句数在 3..4 之间才出题——两句太易，五句以上选项会长得读不完。
     */
    private fun buildOrder(chapter: Chapter, round: Int): LearningTask? {
        val parts = chapter.plainOriginal
            .split('，', '。', '；', '？', '！')
            .map { it.trim() }
            .filter { it.length >= 2 }
        if (parts.size !in 3..4) return null
        val rng = Random(chapter.id * 271 + round)

        val correct = parts.joinToString(" / ")
        val wrongs = mutableSetOf<String>()
        var guard = 0
        while (wrongs.size < 3 && guard++ < 40) {
            val shuffled = parts.shuffled(rng).joinToString(" / ")
            if (shuffled != correct) wrongs += shuffled
        }
        if (wrongs.size < 3) return null

        val texts = (listOf(correct) + wrongs).shuffled(rng)
        return LearningTask(
            id = "order-${chapter.id}-$round",
            kind = TaskKind.ORDER,
            chapterId = chapter.id,
            stem = "下列哪一项是本章各句的正确次序？",
            context = "《论语·${chapter.bookName}》${chapter.ref}",
            options = texts.mapIndexed { i, t -> TaskOption(optionIds[i], t) },
            answerId = optionIds[texts.indexOf(correct)],
            explanation = "原文：${chapter.plainOriginal}",
            difficulty = 2,
            origin = "derived",
        )
    }
}

private fun Char.isChineseChar(): Boolean = this in '一'..'鿿'
