package com.mgad.kakaoalertbridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PartnerBlockDetectorTest {

    @Test
    fun mechanical_failures_are_not_blocked() {
        // 실제 자동참여 기계적 실패 문구들(서버 실측) - 참여제한으로 오분류되면 안 된다.
        val mechanical = listOf(
            "견적입찰",
            "[4/8단계] \"견적입찰\" 탭 클릭 실패(클릭 가능한 노드 없음)",
            "접근성 서비스가 켜져있지 않음",
            "상담참여",
            "파트너스 앱 전면 전환 대기 시간 초과",
        )
        for (m in mechanical) {
            assertFalse("오분류: $m", PartnerBlockDetector.detect(listOf(m)).blocked)
        }
    }

    @Test
    fun block_popup_detected_with_count() {
        val texts = listOf(
            "알림",
            "상담 결과가 입력되지 않아 신규 상담 참여가 제한됩니다.",
            "미상담 개수: 3",
            "확인",
        )
        val r = PartnerBlockDetector.detect(texts)
        assertTrue(r.blocked)
        assertEquals(3, r.misangdamCount)
    }

    @Test
    fun block_detected_without_count() {
        val r = PartnerBlockDetector.detect(listOf("신규 상담 참여가 제한됩니다"))
        assertTrue(r.blocked)
        assertNull(r.misangdamCount)
    }

    @Test
    fun misangdam_count_variants() {
        assertEquals(5, PartnerBlockDetector.detect(listOf("상담 참여 제한", "미상담 5")).misangdamCount)
        assertEquals(12, PartnerBlockDetector.detect(listOf("참여가 제한", "미상담개수:12")).misangdamCount)
    }

    @Test
    fun error_message_format() {
        assertEquals("간판의품격 참여 제한(미상담 2건)", PartnerBlockDetector.errorMessage(2))
        assertEquals("간판의품격 참여 제한(미상담)", PartnerBlockDetector.errorMessage(null))
    }
}
