// 게임 하나의 운영 설정 — 어느 디스코드 채널에서 제보를 받고, 어느 슬랙 채널로 알릴지
package com.ljh.gameops.game;

import java.util.List;

/**
 * @param discordChannels 제보를 받을 디스코드 채널. 채널 ID(숫자) 또는 채널 이름(예: 젤리패닉-버그제보) 둘 다 된다.
 * @param slackChannel    이 게임 알림을 보낼 슬랙 채널(ID 또는 #이름). 비우면 공용 기본 채널.
 * @param slackUrgentChannel 심각도 높은 알림 채널. 비우면 공용 긴급 채널, 그것도 없으면 slackChannel.
 * @param botName 슬랙 카드에 보일 봇 이름(예: 보석타워 운영봇). 비우면 공용 봇 이름. SLACK_CUSTOM_IDENTITY=true 일 때만 쓴다
 * @param icon    슬랙 카드에 보일 봇 아이콘 주소(공개 https 그림). 비우면 공용 봇 아이콘
 * @param repo    게임 코드가 있는 깃허브 저장소(소유자/이름). 「개발 AI에게 넘기기」가 여기에 이슈를 만든다. 비우면 못 넘김
 */
public record GameSpec(String code, String name, List<String> discordChannels,
                       String slackChannel, String slackUrgentChannel, String botName, String icon, String repo) {
    public GameSpec {
        discordChannels = discordChannels == null ? List.of() : List.copyOf(discordChannels);
    }

    public GameSpec(String code, String name, List<String> discordChannels, String slackChannel, String slackUrgentChannel) {
        this(code, name, discordChannels, slackChannel, slackUrgentChannel, null, null, null);
    }

    public GameSpec(String code, String name, List<String> discordChannels, String slackChannel, String slackUrgentChannel,
                    String botName, String icon) {
        this(code, name, discordChannels, slackChannel, slackUrgentChannel, botName, icon, null);
    }
}
