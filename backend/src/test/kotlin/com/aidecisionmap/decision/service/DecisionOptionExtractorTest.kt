package com.aidecisionmap.decision.service

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DecisionOptionExtractorTest {
    @Test
    fun `extracts explicit alternatives across food shopping travel and career topics`() {
        val cases = mapOf(
            "짜장면을 먹을지 탕수육을 먹을지 고민이야" to listOf("짜장면", "탕수육"),
            "짜장과 짬뽕으로 점심메뉴를 고민해" to listOf("짜장면", "짬뽕"),
            "오늘 점심은 짜장면과 짬뽕 중 뭐 먹을까?" to listOf("짜장면", "짬뽕"),
            "아이폰 vs 갤럭시" to listOf("아이폰", "갤럭시"),
            "제주도와 부산 중 어디로 갈까?" to listOf("제주도", "부산"),
            "대학원 진학 또는 취업" to listOf("대학원 진학", "취업"),
            "선택지: 맥북 / 윈도우 노트북 / 태블릿" to listOf("맥북", "윈도우 노트북", "태블릿"),
            "후보: 서울, 부산, 대전" to listOf("서울", "부산", "대전"),
            "중고차 vs 신차" to listOf("중고차", "신차"),
        )
        cases.forEach { (input, expected) ->
            assertEquals(expected, DecisionOptionExtractor.extract(input).map { it.name }, input)
        }
    }

    @Test
    fun `does not invent alternatives from filler or one option`() {
        listOf("네?", "짜장면", "고민이 많아요", "진로를 결정하고 싶어", "주거비와 통근시간 때문에 이사 고민", "가격은 짬뽕이 500원 더 비쌈", "짜장면 / ").forEach {
            assertTrue(DecisionOptionExtractor.extract(it).isEmpty(), it)
        }
    }

    @Test
    fun `does not classify conditional Korean phrases as food`() {
        kotlin.test.assertFalse(DecisionOptionExtractor.isFood("나라면 회사에 남을까?"))
    }
}
