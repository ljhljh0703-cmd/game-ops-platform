// 플레이어에게 직접 알리는 쪽(디스코드 등) — 이슈가 해결되면 제보한 글에 답장한다. 실패하면 예외(대기열이 다시 보낸다)
package com.ljh.gameops.notify;

public interface PlayerNotifier {
    /** 답장한 글 수를 돌려준다. 답장할 곳이 없으면 0. */
    int issueResolved(long issueId);
}
