// 감사 기록 저장소
package com.ljh.gameops.audit;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface AuditLogRepository extends JpaRepository<AuditLog, Long> {
    List<AuditLog> findByTargetOrderByIdAsc(String target);
}
