package com.moodlepulse.app.summarizer

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.util.concurrent.TimeUnit

data class SummarizedResult(
    val summary: String,
    val cleanFullText: String,
    val tags: List<String>
)

object AnnouncementSummarizer {

    private val json = Json { ignoreUnknownKeys = true }

    private val aiClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .build()
    }

    private val tagRules: List<Pair<String, List<String>>> = listOf(
        "考試" to listOf("期中", "期末", "小考", "補考", "測驗", "考試", "midterm", "final", "exam", "quiz"),
        "調課/停課" to listOf("停課", "調課", "補課", "改為線上", "遠距", "教室異動", "換教室", "cancel", "reschedule", "online"),
        "作業" to listOf("作業", "報告", "繳交", "補交", "展延", "延期", "截止", "期限", "assignment", "homework", "deadline", "due", "submit"),
        "點名/成績" to listOf("點名", "簽到", "成績", "配分", "加分", "attendance", "grade", "score"),
        "重要" to listOf("務必", "請注意", "提醒", "緊急", "important", "urgent", "reminder", "notice")
    )

    private val dateTimeRegex = Regex(
        """(\d{4}[/-]\d{1,2}[/-]\d{1,2}|\d{1,2}[/月]\d{1,2}日?|\d{1,2}:\d{2}|週[一二三四五六日天]|星期[一二三四五六日天])"""
    )

    private val boilerplatePrefixes = listOf(
        "dear all", "dear students", "hi all", "hello everyone",
        "各位同學好", "同學們好", "大家好", "各位好",
        "此信件為系統自動發送", "請勿直接回覆此信", "sent from my"
    )

    fun htmlToCleanText(html: String): String {
        if (html.isBlank()) return ""
        val withLineHints = html
            .replace(Regex("(?i)<br\\s*/?>"), "\n")
            .replace(Regex("(?i)</(p|div|li|h[1-6]|tr)>"), "\n")
        val doc: Document = Jsoup.parse(withLineHints)
        doc.outputSettings().prettyPrint(false)
        val rawText = doc.wholeText()

        return rawText
            .replace("\r\n", "\n")
            .replace(Regex("[ \\t\\xA0]+"), " ")
            .replace(Regex("\\n{3,}"), "\n\n")
            .lines()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .joinToString("\n")
    }

    fun summarizeOffline(title: String, htmlBody: String, maxChars: Int = 96): SummarizedResult {
        val cleanText = htmlToCleanText(htmlBody)
        val combinedText = "$title\n$cleanText"
        val tags = extractTags(combinedText)

        if (cleanText.isBlank()) {
            return SummarizedResult(
                summary = title.trim().take(maxChars),
                cleanFullText = title.trim(),
                tags = tags
            )
        }

        val sentences = splitIntoSentences(cleanText)
            .filterNot { isBoilerplate(it) }

        if (sentences.isEmpty()) {
            val fallback = cleanText.replace("\n", " ").take(maxChars)
            return SummarizedResult(summary = fallback, cleanFullText = cleanText, tags = tags)
        }

        val scored = sentences.mapIndexed { idx, sentence ->
            sentence to scoreSentence(sentence, idx, title)
        }.sortedByDescending { it.second }

        val picked = mutableListOf<String>()
        var currentLen = 0
        for ((sentence, _) in scored) {
            val candidate = sentence.trim()
            if (candidate.isBlank()) continue
            if (picked.isEmpty()) {
                picked += candidate
                currentLen = candidate.length
                if (currentLen >= maxChars * 0.72) break
            } else if (currentLen + candidate.length + 2 <= maxChars) {
                picked += candidate
                break
            }
        }

        val joined = picked.joinToString("；")
        val finalSummary = if (joined.length > maxChars) {
            joined.take(maxChars - 1).trimEnd() + "…"
        } else {
            joined
        }

        return SummarizedResult(
            summary = finalSummary,
            cleanFullText = cleanText,
            tags = tags
        )
    }

    suspend fun summarizeWithOptionalAi(
        title: String,
        htmlBody: String,
        geminiApiKey: String?
    ): SummarizedResult {
        val offline = summarizeOffline(title, htmlBody)
        val key = geminiApiKey?.trim().orEmpty()
        if (key.isEmpty() || offline.cleanFullText.length < 45) {
            return offline
        }

        val aiText = withContext(Dispatchers.IO) {
            runCatching {
                callGeminiSummary(key, title, offline.cleanFullText.take(1800))
            }.getOrNull()
        }

        return if (!aiText.isNullOrBlank()) {
            offline.copy(summary = aiText.trim().take(120))
        } else {
            offline
        }
    }

    private fun callGeminiSummary(apiKey: String, title: String, cleanBody: String): String? {
        val prompt = """
            請用繁體中文將以下大學課程公告濃縮成 1 到 2 句重點摘要（不超過 70 個中文字，不要加開頭問候語，保留時間、地點、截止期限或調課考試關鍵資訊）：
            標題：$title
            內容：$cleanBody
        """.trimIndent()

        val bodyJson = buildJsonObject {
            put(
                "contents",
                buildJsonArray {
                    add(
                        buildJsonObject {
                            put(
                                "parts",
                                buildJsonArray {
                                    add(
                                        buildJsonObject {
                                            put("text", prompt)
                                        }
                                    )
                                }
                            )
                        }
                    )
                }
            )
        }.toString()

        val url = "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent?key=$apiKey"
        val request = Request.Builder()
            .url(url)
            .post(bodyJson.toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()

        aiClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            val raw = response.body?.string() ?: return null
            val root = json.parseToJsonElement(raw).jsonObject
            val candidates = root["candidates"]?.jsonArray ?: return null
            val first = candidates.firstOrNull()?.jsonObject ?: return null
            val parts = first["content"]?.jsonObject?.get("parts")?.jsonArray ?: return null
            return parts.firstOrNull()?.jsonObject?.get("text")?.jsonPrimitive?.content
        }
    }

    private fun extractTags(text: String): List<String> {
        val lower = text.lowercase()
        val matched = mutableListOf<String>()
        for ((tag, keywords) in tagRules) {
            if (keywords.any { kw -> lower.contains(kw.lowercase()) }) {
                matched += tag
            }
        }
        return matched.take(3)
    }

    private fun splitIntoSentences(cleanText: String): List<String> {
        return cleanText
            .split(Regex("[\\n。！？!?]+"))
            .map { it.trim() }
            .filter { it.length >= 4 }
    }

    private fun isBoilerplate(sentence: String): Boolean {
        val lower = sentence.lowercase().trim()
        return boilerplatePrefixes.any { lower.startsWith(it) } ||
            (lower.length <= 10 && (lower.contains("敬上") || lower.contains("best regards") || lower.contains("thanks")))
    }

    private fun scoreSentence(sentence: String, index: Int, title: String): Int {
        var score = 0
        val lower = sentence.lowercase()

        if (index == 0) score += 12
        if (index == 1) score += 6
        if (dateTimeRegex.containsMatchIn(sentence)) score += 28
        if (sentence.contains("教室") || sentence.contains("館") || lower.contains("room")) score += 12

        for ((_, keywords) in tagRules) {
            if (keywords.any { lower.contains(it.lowercase()) }) {
                score += 18
            }
        }

        if (sentence.length in 14..75) score += 8
        if (title.isNotBlank() && sentence.equals(title.trim(), ignoreCase = true)) score -= 15
        return score
    }
}
