// 이슈 저장소
package com.ljh.gameops.issue;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface IssueRepository extends JpaRepository<Issue, Long> {
    List<Issue> findByGameIdAndStatusIn(Long gameId, Collection<IssueStatus> statuses);

    Optional<Issue> findByGameIdAndNormalizedKeyAndStatusIn(Long gameId, String key, Collection<IssueStatus> statuses);

    List<Issue> findByGameIdOrderByUpdatedAtDesc(Long gameId);

    List<Issue> findByGameIdAndStatusOrderByUpdatedAtDesc(Long gameId, IssueStatus status);

    @Query("select count(i) from Issue i where i.gameId = ?1 and i.createdAt >= ?2 and i.createdAt < ?3")
    long countCreatedBetween(Long gameId, OffsetDateTime from, OffsetDateTime to);

    @Query("select count(i) from Issue i where i.gameId = ?1 and i.resolvedAt >= ?2 and i.resolvedAt < ?3")
    long countResolvedBetween(Long gameId, OffsetDateTime from, OffsetDateTime to);
}
