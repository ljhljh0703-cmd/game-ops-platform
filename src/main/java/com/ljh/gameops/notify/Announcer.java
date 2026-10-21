// 플레이어에게 공지하는 쪽(디스코드) — 패치노트를 게임 채널에 올린다. 실패하면 예외(대기열이 다시 보낸다)
package com.ljh.gameops.notify;

public interface Announcer {
    /** 올린 메시지 ID */
    String announce(String channelId, String title, String text);
}
