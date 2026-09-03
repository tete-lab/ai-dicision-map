package com.aidecisionmap.decision.service

import com.aidecisionmap.decision.domain.DecisionOption

/** Conservative local recovery for explicit alternatives; never invents candidates. */
internal object DecisionOptionExtractor {
    private val separator = Regex("\\s*(?:vs\\.?|versus|/|,|\\n|또는|아니면|혹은)\\s*|(?:이랑|랑|와|과|하고)\\s+", RegexOption.IGNORE_CASE)
    private val action = Regex("(?:을|를|으로|로)?\\s*(?:먹을지|마실지|살지|갈지|고를지|선택할지|구매할지)")
    private val foods = listOf("짜장면", "짜장", "자장면", "짬뽕", "탕수육", "김밥", "라면", "피자", "치킨", "햄버거", "초밥", "국밥", "비빔밥", "떡볶이", "샐러드", "파스타", "돈까스", "돈가스")
    private val foodWord = Regex("(?<![가-힣])(?:${foods.joinToString("|")})(?:을|를|과|와|이랑|랑|하고|이|은|는)?(?=\\s|$|[/,.?!])")

    fun isFood(text: String): Boolean =
        !Regex("창업|사업|투자|치료|알레르기|질환|퇴사|이직|이사").containsMatchIn(text) &&
            (foodWord.containsMatchIn(text) || Regex("점심|저녁 메뉴|아침 메뉴|먹을지|음식|메뉴 선택").containsMatchIn(text))

    fun extract(text: String): List<DecisionOption> {
        val input = text.trim().removePrefix("사용자 답변:").trim()
        val actionParts = action.split(input)
        val candidates = if (action.findAll(input).count() >= 2) actionParts.dropLast(1)
        else separator.split(input)
        if (candidates.size !in 2..8) return emptyList()
        val names = candidates.map(::clean).distinct()
        // Reject prose, filler and oversized fragments rather than silently interpreting them as options.
        if (names.size !in 2..8 || names.any { it.isBlank() || it.length > 60 || Regex("[?!。]|중요|비싸|저렴|좋은데|있는데|때문|싶어|싶다").containsMatchIn(it) }) return emptyList()
        return names.mapIndexed { index, name -> DecisionOption("option_${index + 1}", name) }
    }

    private fun clean(raw: String): String {
        var value = raw.trim().trim('"', '\'', '‘', '’', '“', '”')
        value = value.replace(Regex("^(?:아니[,.]?\\s*|선택지(?:는|를)?\\s*[:：]?\\s*|후보(?:는|를)?\\s*[:：]?\\s*|비교할 대상은\\s*)"), "")
        value = value.replace(Regex("^(?:(?:오늘|이번|내일)\\s+)?(?:(?:점심|저녁|아침)(?:메뉴| 메뉴)?(?:은|는|으로)?\\s+)"), "")
        value = value.replace(Regex("\\s+(?:중에서|중에|중|사이에서|사이).*$"), "")
        value = value.replace(Regex("(?:으로|로)\\s*(?:점심|저녁|아침|메뉴).*$"), "")
        value = value.replace(Regex("\\s*(?:(?:으로|로|을|를)?\\s*(?:먹을까|마실까|살까|갈까|고를까|먹을지|살지|갈지)|고민|어떤|뭐가|비교해|골라).*$"), "")
        value = value.trim().trim('"', '\'', '.', '?', '!', '‘', '’', '“', '”')
        return when (value) { "짜장", "자장면" -> "짜장면"; else -> value }
    }
}
