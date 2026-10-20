// 게임 하나의 운영 설정 — 어느 디스코드 채널에서 제보를 받고, 어느 슬랙 채널로 알릴지
package com.ljh.gameops.game;

import java.util.List;

/**
 * @param discordChannels 제보를 받을 디스코드 채널. 채널 ID(숫자) 또는 채널 이름(예: 젤리패닉-버그제보) 둘 다 된다.
 * @param slackChannel    이 게임 알림을 보낼 슬랙 채널(ID 또는 #이름). 비우면 공용 기본 채널.
 * @param slackUrgentChannel 심각도 높은 알림 채널. 비우면 공용 긴급 채널, 그것도 없으면 slackChannel.
 */
public record GameSpec(String code, String name, List<String> discordChannels,
                       String slackChannel, String slackUrgentChannel) {
    public GameSpec {
        discordChannels = discordChannels == null ? List.of() : List.copyOf(discordChannels);
    }
}
