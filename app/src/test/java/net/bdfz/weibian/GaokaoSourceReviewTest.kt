package net.bdfz.weibian

import java.io.File
import net.bdfz.weibian.content.ContentBundle
import net.bdfz.weibian.domain.gaokaoFeedbackPrompt
import net.bdfz.weibian.domain.gaokaoFeedbackScore
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class GaokaoSourceReviewTest {
    private fun fixture() = JSONObject(File("src/test/resources/exam-source-review-fixture.json").readText())

    private fun bundle(): ContentBundle {
        val json = JSONObject(File("src/main/assets/content.json").readText())
        val groups = json.getJSONArray("gaokao")
        val reviews = fixture().getJSONArray("groups")
        // CI bootstraps the supported published bundle. Exercise the new parser
        // using a test-only projection without changing that packaged content.
        for (i in 0 until groups.length()) {
            val group = groups.getJSONObject(i)
            if (group.has("sourceReview")) continue
            val review = (0 until reviews.length()).map { reviews.getJSONObject(it) }
                .first { it.getString("id") == group.getString("id") }
            group.put("sourceReview", review.getJSONObject("sourceReview"))
            val questions = group.getJSONArray("questions")
            val reviewedQuestions = review.getJSONArray("questions")
            for (j in 0 until questions.length()) {
                val question = questions.getJSONObject(j)
                val reviewedQuestion = (0 until reviewedQuestions.length()).map { reviewedQuestions.getJSONObject(it) }
                    .first { it.getString("id") == question.getString("id") }
                question.put("sourceReview", reviewedQuestion.getJSONObject("sourceReview"))
            }
        }
        return ContentBundle.parse(json.toString(), "test")
    }

    @Test fun `packaged content is either exact reviewed candidate or exact supported published bundle`() {
        val json = JSONObject(File("src/main/assets/content.json").readText())
        val groups = json.getJSONArray("gaokao")
        val count = (0 until groups.length()).count { groups.getJSONObject(it).has("sourceReview") }
        val manifest = JSONObject(File("src/main/assets/content-manifest.json").readText())
        if (count == 0) {
            val published = JSONObject(File("../content/public-content-lock.json").readText()).getJSONObject("manifest")
            assertEquals(published.getString("sha256"), manifest.getString("sha256"))
            assertFalse("A generated candidate cannot omit its reviews", manifest.has("sourceInputLockSha256"))
        } else {
            assertEquals(groups.length(), count)
            assertEquals(fixture().getString("sourceBundleSha256"), manifest.getString("sha256"))
        }
    }

    @Test fun `all historical question IDs parse with reviewed answers`() {
        val content = bundle()
        assertEquals(7, content.gaokao.size)
        assertEquals(23, content.gaokao.sumOf { it.questions.size })
        assertTrue(content.gaokao.all { it.sourceReview != null && it.questions.all { q -> q.sourceReview != null } })
        val item = content.gaokao("gk-2015-lunyu")!!
        for (id in listOf("q1", "q15")) {
            val q = item.questions.first { it.id == id }
            assertTrue(q.displayAnswer.contains("曾皙、孔子、曾皙、孔子"))
            assertEquals(1, q.feedbackMaxScore)
        }
        assertTrue(item.modelAnswer.isNotBlank())
    }

    @Test fun `uncertain subpart scores cannot become numeric feedback`() {
        for (year in listOf(2019, 2023)) {
            val item = bundle().gaokao("gk-$year-lunyu")!!
            for (question in item.questions) {
                assertNull(question.feedbackMaxScore)
                val prompt = gaokaoFeedbackPrompt(item, question, "测试作答")
                assertTrue(prompt.contains("只给评语"))
                assertFalse(prompt.contains("X/6"))
                assertNull(gaokaoFeedbackScore("得分6/6", question.feedbackMaxScore))
            }
        }
    }

    @Test fun `known printed score requires matching denominator and valid range`() {
        val item = bundle().gaokao("gk-2020-lunyu")!!
        val q = item.questions.first { it.id == "q1" }
        assertEquals(2, q.feedbackMaxScore)
        assertEquals(1, gaokaoFeedbackScore("学习参考得分1/2", q.feedbackMaxScore))
        assertNull(gaokaoFeedbackScore("1/6", q.feedbackMaxScore))
        assertNull(gaokaoFeedbackScore("3/2", q.feedbackMaxScore))
        assertNull(gaokaoFeedbackScore("-1/2", q.feedbackMaxScore))
        assertNull(gaokaoFeedbackScore("1.5/2", q.feedbackMaxScore))
        assertNull(gaokaoFeedbackScore("无法批改", q.feedbackMaxScore))
    }

    @Test fun `corrected material and source limits reach future feedback`() {
        val item = bundle().gaokao("gk-2023-lunyu")!!
        assertTrue(item.material.contains("不已知"))
        assertTrue(item.displayMaterial.contains("不己知"))
        val q = item.questions.first { it.id == "q1" }
        val prompt = gaokaoFeedbackPrompt(item, q, "测试作答")
        assertTrue(prompt.contains("不己知"))
        assertFalse(prompt.contains("不已知"))
        assertTrue(prompt.contains("未显示逐项配分"))
        assertTrue(prompt.contains("相称、符合"))
    }

    @Test fun `old bundles still parse without reinterpreting historical fields`() {
        val json = JSONObject(File("src/main/assets/content.json").readText())
        val groups = json.getJSONArray("gaokao")
        for (i in 0 until groups.length()) {
            val group = groups.getJSONObject(i)
            group.remove("sourceReview")
            val questions = group.getJSONArray("questions")
            for (j in 0 until questions.length()) questions.getJSONObject(j).remove("sourceReview")
        }
        val old = ContentBundle.parse(json.toString(), "old")
        assertEquals(23, old.gaokao.sumOf { it.questions.size })
        for (item in old.gaokao) {
            assertNull(item.sourceReview)
            assertEquals(item.material, item.displayMaterial)
            for (question in item.questions) assertEquals(question.score, question.feedbackMaxScore)
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun `publisher reproduction cannot silently become official`() {
        val json = JSONObject(File("src/main/assets/content.json").readText())
        val review = fixture().getJSONArray("groups").getJSONObject(0).getJSONObject("sourceReview")
        review.put("officialSource", true)
        json.getJSONArray("gaokao").getJSONObject(0).put("sourceReview", review)
        ContentBundle.parse(json.toString(), "invalid")
    }
}
