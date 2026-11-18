package com.mgad.kakaoalertbridge

// 간판의품격 파트너스 앱이 "미상담" 누적으로 신규 상담 참여를 막는 팝업을 식별한다(순수 로직 - 단위 테스트 가능).
// 예: "상담 결과가 입력되지 않아 신규 상담 참여가 제한됩니다. 미상담 개수: 3"
//
// 감지되면 PartnersAutoParticipateService가 auto-participate-result에 partner_blocked=true와 미상담
// 개수를 실어 보내고(서버 A-2의 하루 1회 요약과 맞물림) 팝업을 닫는다. 이건 기계적 실패가 아니라
// 사람이 미상담을 처리해야 풀리는 상태라, 재시도하지 않고 완료(BLOCKED)로 본다.
object PartnerBlockDetector {
    // 참여 제한 팝업을 가리키는 키워드(서버 _PARTNER_BLOCK_KEYWORDS와 일관). 기계적 실패 문구
    // (탭 클릭 실패/접근성 꺼짐/타임아웃 등)에는 없는 표현이라 오탐 위험이 낮다.
    private val BLOCK_KEYWORDS = listOf("참여가 제한", "참여 제한", "상담 제한", "상담참여 제한")
    private val MISANGDAM_REGEX = Regex("미상담\\s*(?:개수)?\\s*[:：]?\\s*(\\d+)")

    data class Result(val blocked: Boolean, val misangdamCount: Int?)

    // 화면에서 수집한 텍스트들(노드 text/contentDescription)을 받아 판정.
    fun detect(texts: List<String>): Result {
        val joined = texts.joinToString(" ")
        val blocked = BLOCK_KEYWORDS.any { joined.contains(it) }
        if (!blocked) return Result(false, null)
        val count = MISANGDAM_REGEX.find(joined)?.groupValues?.getOrNull(1)?.toIntOrNull()
        return Result(true, count)
    }

    // 서버에 보낼 사람용 사유 문자열.
    fun errorMessage(count: Int?): String =
        if (count != null) "간판의품격 참여 제한(미상담 ${count}건)" else "간판의품격 참여 제한(미상담)"
}
