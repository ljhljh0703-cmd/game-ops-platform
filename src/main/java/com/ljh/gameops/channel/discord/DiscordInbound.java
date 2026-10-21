// 디스코드에서 들어온 제보 한 건 — 디스코드 라이브러리 타입과 분리해 테스트하기 쉽게
package com.ljh.gameops.channel.discord;

import com.ljh.gameops.report.ReproInfo;

/**
 * @param replyTo 해결되면 답장할 곳 「실제 채널 ID:메시지 ID」(스레드면 스레드 ID). 명령(/report)은 나중에 답장할 수 없어 null
 * @param repro   /report 입력칸의 버전·기기·재현 순서. 채널 글이면 null(글에서 찾는다)
 */
public record DiscordInbound(String externalId, String channelId, String channelName, String authorId, boolean fromBot,
                             String gameCode, String content, String attachmentUrls, String replyTo, ReproInfo repro) {
    public DiscordInbound(String externalId, String channelId, String channelName, String authorId, boolean fromBot,
                          String gameCode, String content, String attachmentUrls, String replyTo) {
        this(externalId, channelId, channelName, authorId, fromBot, gameCode, content, attachmentUrls, replyTo, null);
    }

    public DiscordInbound(String externalId, String channelId, String channelName, String authorId, boolean fromBot,
                          String gameCode, String content, String attachmentUrls) {
        this(externalId, channelId, channelName, authorId, fromBot, gameCode, content, attachmentUrls, null);
    }
}
