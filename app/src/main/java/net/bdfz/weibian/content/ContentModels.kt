package net.bdfz.weibian.content

import org.json.JSONArray
import org.json.JSONObject

/**
 * 内容模型 —— 对应 content/build_content.py 产出的 `lunyu-content-v1` 内容包。
 *
 * 内容是不可变的版本化资产：随 APK 附带一份，也可由内容接口整包替换，
 * 因此这里全部是只读数据类，不进 Room。可变的学习记录另存于 Room。
 */

data class Annotation(
    /** 原书注释序号（已修复上游重号/缺号，与正文标记对齐） */
    val number: Int,
    /** 原始标记字符，如 "⑴"；上游缺失时为空串 */
    val marker: String,
    /** 词目，如 "不惑"；个别条目无词目 */
    val term: String,
    val gloss: String,
)

data class Chapter(
    val id: Int,
    /** 篇章号，如 "2.4" */
    val ref: String,
    /** 第几篇，1..20 */
    val book: Int,
    val bookName: String,
    /** 篇内第几章 */
    val index: Int,
    val title: String,
    /** 含 ⑴⑵ 注释标记的原文 */
    val original: String,
    /** 去掉注释标记的原文，用于朗读、检索、填空 */
    val plainOriginal: String,
    val translation: String,
    val annotations: List<Annotation>,
    val charCount: Int,
    /** 正文中出现的注释标记序号，按出现次序 */
    val markersInText: List<Int>,
    /** 上游缺注释、无法解析的标记序号；这些标记按纯文本渲染 */
    val unresolvedMarkers: List<Int>,
    val gaokaoIds: List<String>,
    val questionCount: Int,
) {
    fun annotationFor(number: Int): Annotation? = annotations.firstOrNull { it.number == number }
}

data class Book(
    val book: Int,
    val name: String,
    val chapterCount: Int,
    val charCount: Int,
)

data class Concept(
    val id: String,
    val name: String,
    val pinyin: String,
    val gloss: String,
    val detail: String,
    val refs: List<Int>,
)

data class Figure(
    val id: String,
    val name: String,
    val style: String,
    val role: String,
    val gloss: String,
    val refs: List<Int>,
)

data class BankOption(
    val id: String,
    val text: String,
    /** 为什么这个选项不对 —— 答题后展示，是错项的诊断而非单纯判错 */
    val why: String,
)

data class BankItem(
    val id: String,
    /** comprehension / interpretation / concept / linkage / conflict / figure / application / misread */
    val type: String,
    val refs: List<Int>,
    val concepts: List<String>,
    val figures: List<String>,
    /** 1 入门 · 2 进阶 · 3 精研 */
    val difficulty: Int,
    /** understand / apply / analyze / evaluate */
    val cognitive: String,
    val purpose: String,
    val stem: String,
    val context: String,
    val options: List<BankOption>,
    val answerId: String,
    val explanation: String,
) {
    val primaryRef: Int get() = refs.firstOrNull() ?: 0
}

data class GaokaoQuestion(
    val id: String,
    val prompt: String,
    val score: Int?,
    val answer: String,
    val explanation: String,
    val knowledgePoints: List<String>,
)

data class GaokaoItem(
    val id: String,
    val year: Int,
    val province: String,
    val source: String,
    val sectionLabel: String,
    val topic: String,
    val material: String,
    val questions: List<GaokaoQuestion>,
    val referenceAnswer: String,
    val modelAnswer: String,
    val answerSource: String,
    /** 该题所考的《论语》章句 id */
    val passages: List<Int>,
)

/** 一份完整的、已建好索引的内容包。 */
class ContentBundle(
    val version: String,
    val chapters: List<Chapter>,
    val books: List<Book>,
    val concepts: List<Concept>,
    val figures: List<Figure>,
    val bank: List<BankItem>,
    val gaokao: List<GaokaoItem>,
    /** 旧站重复章 id → 现行章 id，用于迁移既有进度 */
    private val aliases: Map<Int, Int>,
) {
    private val chapterById: Map<Int, Chapter> = chapters.associateBy { it.id }
    private val bankByChapter: Map<Int, List<BankItem>> =
        bank.flatMap { item -> item.refs.map { it to item } }
            .groupBy({ it.first }, { it.second })
    private val gaokaoById: Map<String, GaokaoItem> = gaokao.associateBy { it.id }
    private val conceptById: Map<String, Concept> = concepts.associateBy { it.id }
    private val figureById: Map<String, Figure> = figures.associateBy { it.id }

    val chapterCount: Int get() = chapters.size

    /** 源数据里被折叠掉的重复章条数。 */
    val aliasCount: Int get() = aliases.size

    /** 全部「旧 id → 现行 id」映射，供迁移既有站点进度时使用。 */
    val aliasPairs: Map<Int, Int> get() = aliases

    /** 解析章 id，自动把旧的重复章 id 归并到现行章。 */
    fun canonicalId(id: Int): Int = aliases[id] ?: id

    fun chapter(id: Int): Chapter? = chapterById[canonicalId(id)]

    fun chaptersOf(book: Int): List<Chapter> = chapters.filter { it.book == book }

    fun questionsFor(chapterId: Int): List<BankItem> =
        bankByChapter[canonicalId(chapterId)].orEmpty()

    fun gaokaoFor(chapterId: Int): List<GaokaoItem> =
        chapter(chapterId)?.gaokaoIds?.mapNotNull { gaokaoById[it] }.orEmpty()

    fun gaokao(id: String): GaokaoItem? = gaokaoById[id]

    fun concept(id: String): Concept? = conceptById[id]

    fun figure(id: String): Figure? = figureById[id]

    /** 全文检索：原文、译文、注释都在检索范围内。 */
    fun search(query: String, limit: Int = 60): List<Chapter> {
        val needle = query.trim()
        if (needle.isEmpty()) return emptyList()
        return chapters.asSequence()
            .filter { chapter ->
                chapter.plainOriginal.contains(needle) ||
                    chapter.translation.contains(needle) ||
                    chapter.title.contains(needle) ||
                    chapter.annotations.any { it.term.contains(needle) || it.gloss.contains(needle) }
            }
            .take(limit)
            .toList()
    }

    companion object {
        fun parse(json: String, version: String): ContentBundle {
            val root = JSONObject(json)
            return ContentBundle(
                version = version,
                chapters = root.getJSONArray("chapters").map { it.toChapter() },
                books = root.getJSONArray("books").map { it.toBook() },
                concepts = root.getJSONArray("concepts").map { it.toConcept() },
                figures = root.getJSONArray("figures").map { it.toFigure() },
                bank = root.getJSONArray("bank").map { it.toBankItem() },
                gaokao = root.getJSONArray("gaokao").map { it.toGaokaoItem() },
                aliases = root.optJSONObject("aliases")?.let { obj ->
                    obj.keys().asSequence().associate { key -> key.toInt() to obj.getInt(key) }
                }.orEmpty(),
            )
        }
    }
}

// ---------------------------------------------------------------------------
// JSON 解析辅助 —— 用平台自带的 org.json，避免为一次冷启动解析引入序列化框架。
// ---------------------------------------------------------------------------

private inline fun <T> JSONArray.map(transform: (JSONObject) -> T): List<T> =
    ArrayList<T>(length()).also { out ->
        for (i in 0 until length()) out.add(transform(getJSONObject(i)))
    }

private fun JSONArray?.toIntList(): List<Int> {
    if (this == null) return emptyList()
    return ArrayList<Int>(length()).also { out ->
        for (i in 0 until length()) out.add(getInt(i))
    }
}

private fun JSONArray?.toStringList(): List<String> {
    if (this == null) return emptyList()
    return ArrayList<String>(length()).also { out ->
        for (i in 0 until length()) out.add(getString(i))
    }
}

private fun JSONObject.toChapter() = Chapter(
    id = getInt("id"),
    ref = getString("ref"),
    book = getInt("book"),
    bookName = getString("bookName"),
    index = getInt("index"),
    title = getString("title"),
    original = getString("original"),
    plainOriginal = getString("plainOriginal"),
    translation = getString("translation"),
    annotations = optJSONArray("annotations")?.map { it.toAnnotation() }.orEmpty(),
    charCount = optInt("charCount"),
    markersInText = optJSONArray("markersInText").toIntList(),
    unresolvedMarkers = optJSONArray("unresolvedMarkers").toIntList(),
    gaokaoIds = optJSONArray("gaokaoIds").toStringList(),
    questionCount = optInt("questionCount"),
)

private fun JSONObject.toAnnotation() = Annotation(
    number = optInt("number", optInt("index")),
    marker = optString("marker"),
    term = optString("term"),
    gloss = optString("gloss"),
)

private fun JSONObject.toBook() = Book(
    book = getInt("book"),
    name = getString("name"),
    chapterCount = getInt("chapterCount"),
    charCount = optInt("charCount"),
)

private fun JSONObject.toConcept() = Concept(
    id = getString("id"),
    name = getString("name"),
    pinyin = optString("pinyin"),
    gloss = optString("gloss"),
    detail = optString("detail"),
    refs = optJSONArray("refs").toIntList(),
)

private fun JSONObject.toFigure() = Figure(
    id = getString("id"),
    name = getString("name"),
    style = optString("style"),
    role = optString("role"),
    gloss = optString("gloss"),
    refs = optJSONArray("refs").toIntList(),
)

private fun JSONObject.toBankItem() = BankItem(
    id = getString("id"),
    type = getString("type"),
    refs = optJSONArray("refs").toIntList(),
    concepts = optJSONArray("concepts").toStringList(),
    figures = optJSONArray("figures").toStringList(),
    difficulty = optInt("difficulty", 1),
    cognitive = optString("cognitive", "understand"),
    purpose = optString("purpose"),
    stem = getString("stem"),
    context = optString("context"),
    options = optJSONArray("options")?.map {
        BankOption(
            id = it.getString("id"),
            text = it.getString("text"),
            why = it.optString("why"),
        )
    }.orEmpty(),
    answerId = getString("answerId"),
    explanation = optString("explanation"),
)

private fun JSONObject.toGaokaoItem() = GaokaoItem(
    id = getString("id"),
    year = optInt("year"),
    province = optString("province"),
    source = optString("source"),
    sectionLabel = optString("sectionLabel"),
    topic = optString("topic"),
    material = optString("material"),
    questions = optJSONArray("questions")?.map {
        GaokaoQuestion(
            id = it.optString("id"),
            prompt = it.optString("prompt"),
            score = if (it.isNull("score")) null else it.optInt("score"),
            answer = it.optString("answer"),
            explanation = it.optString("explanation"),
            knowledgePoints = it.optJSONArray("knowledgePoints").toStringList(),
        )
    }.orEmpty(),
    referenceAnswer = optString("referenceAnswer"),
    modelAnswer = optString("modelAnswer"),
    answerSource = optString("answerSource"),
    passages = optJSONArray("passages").toIntList(),
)
