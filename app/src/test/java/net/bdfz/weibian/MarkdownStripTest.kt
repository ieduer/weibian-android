package net.bdfz.weibian

import net.bdfz.weibian.network.ApiClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * AI 网关会时不时冒出 Markdown，而 App 是纯文本渲染的。
 * 这里锁住剥离行为：记号要去掉，文字本身一个字都不能动。
 */
class MarkdownStripTest {

    private val strip = ApiClient::class.java
        .getDeclaredMethod("stripMarkdown", String::class.java)
        .apply { isAccessible = true }

    private fun run(input: String): String = strip.invoke(ApiClient(), input) as String

    @Test
    fun `去掉加粗但保留文字`() {
        assertEquals("1. 快乐学习：把知识用到实践中", run("**1. 快乐学习：把知识用到实践中**"))
        assertEquals("含义：学到了知识", run("**含义**：学到了知识"))
    }

    @Test
    fun `列表符换成中点`() {
        assertEquals("· 含义：学而时习之", run("* 含义：学而时习之"))
        assertEquals("· 要点", run("-   要点"))
    }

    @Test
    fun `有序列表保留编号`() {
        assertEquals("1. 第一点", run("1) 第一点"))
        assertEquals("2. 第二点", run("2. 第二点"))
    }

    @Test
    fun `去掉标题记号`() {
        assertEquals("小标题", run("### 小标题"))
    }

    @Test
    fun `去掉反引号`() {
        assertEquals("习 指实践", run("`习` 指实践"))
    }

    @Test
    fun `正文里的星号不被误伤`() {
        // 数学式或强调符之外的单星不该被吃掉
        val text = "《论语》共 512 章 * 每章一题"
        assertEquals(text, run(text))
    }

    @Test
    fun `多段混合文本处理干净`() {
        val input = """
            **1. 快乐学习**
            *   **含义**：学到了知识，并在适当的时候去练习。
            *   **要点**：这里的"习"不仅是复习。

            ### 小结
            `学而时习之` 是全书开篇。
        """.trimIndent()
        val out = run(input)
        assertFalse("仍残留 **", out.contains("**"))
        assertFalse("仍残留 ###", out.contains("#"))
        assertFalse("仍残留反引号", out.contains("`"))
        // 文字内容必须还在
        assertEquals(true, out.contains("学到了知识"))
        assertEquals(true, out.contains("学而时习之 是全书开篇"))
    }

    @Test
    fun `纯文本原样返回`() {
        val text = "孔子说：学了，然后按一定的时间去实习它，不也高兴吗？"
        assertEquals(text, run(text))
    }
}
