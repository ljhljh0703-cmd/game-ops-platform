// 대기열이 패치노트 공지를 보낼 때 부르는 쪽(패치노트 서비스가 구현). 알림 묶음이 패치노트 쪽을 직접 알지 않게 사이에 둔다
package com.ljh.gameops.notify;

public interface PatchNoteDelivery {
    PatchCard card(long patchId);

    /** 디스코드에 올리고 공지됨으로 바꾼다 */
    void deliver(long patchId);
}
