// 고친 기록 저장소
package com.ljh.gameops.correction;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface CorrectionRepository extends JpaRepository<Correction, Long> {
    List<Correction> findTop200ByGameIdOrderByCreatedAtDesc(Long gameId);
    List<Correction> findByGameId(Long gameId);
}
