package net.bdfz.weibian

import net.bdfz.weibian.content.ContentBundle
import net.bdfz.weibian.domain.LearningEngine
import net.bdfz.weibian.domain.TaskKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.File

/**
 * 直接解析随包发布的 assets/content.json —— 校验的是真正会装进 APK 的那份内容，
 * 而不是测试里另造的样本。内容出问题，这里就该红。
 */
class ContentBundleTest {

    companion object {
        private lateinit var bundle: ContentBundle

        @BeforeClass
        @JvmStatic
        fun load() {
            val file = File("src/main/assets/content.json")
            assertTrue("找不到内容包：${file.absolutePath}", file.exists())
            bundle = ContentBundle.parse(file.readText(), "test")
        }
    }

    @Test
    fun `全书五百一十二章`() {
        assertEquals(512, bundle.chapterCount)
    }

    @Test
    fun `二十篇齐备且章数与杨伯峻本吻合`() {
        assertEquals(20, bundle.books.size)
        val expected = mapOf(
            1 to 16, 2 to 24, 3 to 26, 4 to 26, 5 to 28, 6 to 30, 7 to 38, 8 to 21,
            9 to 31, 10 to 27, 11 to 26, 12 to 24, 13 to 30, 14 to 44, 15 to 42,
            16 to 14, 17 to 26, 18 to 11, 19 to 25, 20 to 3,
        )
        bundle.books.forEach { book ->
            assertEquals("第 ${book.book} 篇《${book.name}》章数不符", expected[book.book], book.chapterCount)
        }
    }

    @Test
    fun `每章原文与译文都不为空`() {
        bundle.chapters.forEach { chapter ->
            assertTrue("${chapter.title} 原文为空", chapter.original.isNotBlank())
            assertTrue("${chapter.title} 译文为空", chapter.translation.isNotBlank())
            assertTrue("${chapter.title} 去标记原文为空", chapter.plainOriginal.isNotBlank())
        }
    }

    @Test
    fun `正文注释标记基本都能解析`() {
        val annotated = bundle.chapters.filter { it.markersInText.isNotEmpty() }
        val dangling = annotated.filter { it.unresolvedMarkers.isNotEmpty() }
        // 上游 dialogues.json 有两章（雍也 6.7、6.26）确实缺注释，属已知数据缺口
        assertTrue("悬空标记的章数超出已知缺口：${dangling.map { it.title }}", dangling.size <= 2)
    }

    @Test
    fun `重复章别名可解析回现行章`() {
        // 源数据 541 行、512 章，29 条重复折叠为别名
        assertEquals(29, bundle.aliasCount)
        assertEquals(541, bundle.chapterCount + bundle.aliasCount)
        bundle.aliasPairs.forEach { (alias, canonical) ->
            assertEquals(
                "别名 $alias 未指回现行章 $canonical",
                canonical,
                bundle.chapter(alias)?.id,
            )
        }
    }

    @Test
    fun `题库两百一十五道且答案与诊断齐备`() {
        assertEquals(215, bundle.bank.size)
        bundle.bank.forEach { item ->
            val ids = item.options.map { it.id }
            assertTrue("${item.id} 的答案不在选项内", item.answerId in ids)
            assertTrue("${item.id} 选项不足", item.options.size >= 2)
            item.options.filter { it.id != item.answerId }.forEach { option ->
                assertTrue("${item.id} 的错项 ${option.id} 缺 why 诊断", option.why.isNotBlank())
            }
            item.refs.forEach {
                assertNotNull("${item.id} 指向不存在的章 $it", bundle.chapter(it))
            }
        }
    }

    @Test
    fun `高考真题都能落到具体章句或说明为写作题`() {
        assertTrue(bundle.gaokao.isNotEmpty())
        bundle.gaokao.forEach { item ->
            assertTrue("${item.id} 无题目", item.questions.isNotEmpty())
            // 有材料的必须能映射到章句；微写作类无材料，允许为空
            if (item.material.isNotBlank()) {
                assertTrue("${item.id} 未映射到任何章句", item.passages.isNotEmpty())
            }
            item.passages.forEach {
                assertNotNull("${item.id} 指向不存在的章 $it", bundle.chapter(it))
            }
        }
    }

    @Test
    fun `章到真题的反向索引与真题到章一致`() {
        bundle.gaokao.forEach { item ->
            item.passages.forEach { chapterId ->
                val back = bundle.gaokaoFor(chapterId).map { it.id }
                assertTrue("${item.id} 未出现在章 $chapterId 的反查结果里", item.id in back)
            }
        }
    }

    @Test
    fun `学习引擎能为任意章出题且答案在选项内`() {
        val engine = LearningEngine(bundle)
        // 取覆盖各篇的样本，长短章都要试到
        val sample = bundle.chapters.filterIndexed { index, _ -> index % 37 == 0 }
        assertTrue(sample.size >= 10)
        sample.forEach { chapter ->
            val tasks = engine.tasksFor(chapter.id, round = 0)
            assertTrue("${chapter.title} 出不出题", tasks.isNotEmpty())
            tasks.forEach { task ->
                val ids = task.options.map { it.id }
                assertTrue("${task.id} 答案不在选项内", task.answerId in ids)
                assertEquals("${task.id} 选项 id 重复", ids.size, ids.distinct().size)
                assertTrue("${task.id} 选项文本重复", task.options.map { it.text }.distinct().size == ids.size)
                assertTrue("${task.id} 题干为空", task.stem.isNotBlank())
            }
        }
    }

    @Test
    fun `出题稳定可复现同轮次同题异轮次换题`() {
        val engine = LearningEngine(bundle)
        val chapterId = bundle.chapters.first { it.annotations.size >= 3 }.id
        val first = engine.tasksFor(chapterId, round = 0).map { it.id }
        val again = engine.tasksFor(chapterId, round = 0).map { it.id }
        assertEquals("同一轮次出题不稳定", first, again)
        val nextRound = engine.tasksFor(chapterId, round = 1).map { it.id }
        assertTrue("换轮次未换题", nextRound != first)
    }

    @Test
    fun `生成题覆盖原文掌握维度`() {
        val engine = LearningEngine(bundle)
        val kinds = bundle.chapters.take(60)
            .flatMap { engine.tasksFor(it.id, round = 0) }
            .map { it.kind }
            .toSet()
        assertTrue("缺少补字题", TaskKind.CLOZE in kinds)
        assertTrue("缺少释词题", TaskKind.GLOSS in kinds)
        assertTrue("缺少识文题", TaskKind.RECOGNIZE in kinds)
    }

    @Test
    fun `概念与人物引用都能解析`() {
        bundle.concepts.forEach { concept ->
            concept.refs.forEach {
                assertNotNull("概念 ${concept.id} 指向不存在的章 $it", bundle.chapter(it))
            }
        }
        bundle.figures.forEach { figure ->
            figure.refs.forEach {
                assertNotNull("人物 ${figure.id} 指向不存在的章 $it", bundle.chapter(it))
            }
        }
    }

    @Test
    fun `检索能命中已知章句`() {
        assertTrue(bundle.search("学而时习之").any { it.ref == "1.1" })
        assertTrue(bundle.search("四十而不惑").any { it.ref == "2.4" })
        assertTrue(bundle.search("").isEmpty())
    }

    @Test
    fun `界面用字为简体`() {
        // 题库源为繁体，构建期须已转简；抽查常见繁简差异字不应出现
        val traditional = setOf('學', '禮', '義', '爲', '這', '樣', '說', '對', '無', '個')
        val offenders = bundle.bank.filter { item ->
            (item.stem + item.explanation + item.options.joinToString { it.text })
                .any { it in traditional }
        }
        assertTrue("题库仍含繁体：${offenders.take(3).map { it.id }}", offenders.isEmpty())
    }
}
