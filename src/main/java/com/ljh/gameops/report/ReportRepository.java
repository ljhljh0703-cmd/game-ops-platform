// 제보 저장소
package com.ljh.gameops.report;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

public interface ReportRepository extends JpaRepository<Report, Long> {
    Optional<Report> findBySourceChannelAndExternalId(SourceChannel channel, String externalId);

    long countByAuthorHashAndReceivedAtAfter(String authorHash, java.time.OffsetDateTime after);

    /** 게임 하나에 디스코드 제보가 몰리는지(결정 43) */
    long countByGameIdAndSourceChannelAndReceivedAtAfter(Long gameId, SourceChannel channel, java.time.OffsetDateTime after);
}
