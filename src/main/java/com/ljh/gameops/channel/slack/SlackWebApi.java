// 슬랙 쪽으로 거는 호출 두 가지 — 입력 창 열기(views.open), 원래 카드 바꾸기(chat.update).
// 버튼 요청은 3초 안에 답해야 해서 카드 바꾸기는 뒤에서 보낸다. 입력 창은 trigger_id 가 3초 뒤 만료라 바로 연다.
package com.ljh.gameops.channel.slack;

import com.ljh.gameops.notify.IssueCard;
import com.slack.api.model.view.View;

public interface SlackWebApi {
    /** 입력 창을 연다. 실패하면 false(이유는 기록에 남김). */
    boolean openView(String triggerId, View view);

    /** 봇이 올린 카드(채널·ts)를 새 내용으로 바꾼다. 뒤에서 보낸다. */
    void updateCard(String channel, String ts, IssueCard card);

    /** 규칙 반영 카드를 바꾼다(되돌린 뒤). 뒤에서 보낸다. */
    void updateRulesCard(String channel, String ts, com.ljh.gameops.notify.RuleChangeCard card);

    /** 피드백 정리 카드를 바꾼다(개발 AI에게 넘긴 뒤). 뒤에서 보낸다 */
    default void updateDigestCard(String channel, String ts, com.ljh.gameops.notify.DigestCard card) {}

    /** 그 사람의 앱 홈 탭에 화면을 올린다(views.publish). 바로 보낸다 */
    boolean publishHome(String userId, View view);
}
