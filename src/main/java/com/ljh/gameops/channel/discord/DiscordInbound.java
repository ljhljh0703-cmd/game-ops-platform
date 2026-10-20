// 디스코드에서 들어온 제보 한 건 — 디스코드 라이브러리 타입과 분리해 테스트하기 쉽게
package com.ljh.gameops.channel.discord;

public record DiscordInbound(String externalId, String channelId, String channelName, String authorId, boolean fromBot,
                             String gameCode, String content, String attachmentUrls) {}
