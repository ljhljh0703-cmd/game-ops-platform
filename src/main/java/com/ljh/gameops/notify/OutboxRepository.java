// 알림 대기열 저장소 — 여러 서버가 떠도 같은 건을 두 번 보내지 않게 SKIP LOCKED 로 집는다
package com.ljh.gameops.notify;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface OutboxRepository extends JpaRepository<OutboxMessage, Long> {
    @Query(value = """
            SELECT * FROM notification_outbox
            WHERE status = 'PENDING' AND next_attempt_at <= now()
            ORDER BY id
            LIMIT :limit
            FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    List<OutboxMessage> lockDue(int limit);

    List<OutboxMessage> findByTypeOrderByIdAsc(NotificationType type);
}
