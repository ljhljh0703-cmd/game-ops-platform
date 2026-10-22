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

    @org.springframework.data.jpa.repository.Query("""
            select new com.ljh.gameops.issue.IssueCandidate(i.id, i.category, i.autoCategory, i.normalizedText)
            from Issue i where i.gameId = :gameId and i.status in :statuses""")
    List<IssueCandidate> candidates(@org.springframework.data.repository.query.Param("gameId") Long gameId,
                                    @org.springframework.data.repository.query.Param("statuses") Collection<IssueStatus> statuses);

    Optional<Issue> findByGameIdAndNormalizedKeyAndStatusIn(Long gameId, String key, Collection<IssueStatus> statuses);

    List<Issue> findTop5ByGameIdAndStatusInOrderByReportCountDescUpdatedAtDesc(Long gameId, Collection<IssueStatus> statuses);

    List<Issue> findByGameIdAndStatusAndResolvedAtAfter(Long gameId, IssueStatus status, OffsetDateTime after);

    long countByGameIdAndStatusIn(Long gameId, Collection<IssueStatus> statuses);

    List<Issue> findByGameIdOrderByUpdatedAtDesc(Long gameId);

    List<Issue> findByGameIdAndStatusOrderByUpdatedAtDesc(Long gameId, IssueStatus status);

    @Query("select count(i) from Issue i where i.gameId = ?1 and i.createdAt >= ?2 and i.createdAt < ?3")
    long countCreatedBetween(Long gameId, OffsetDateTime from, OffsetDateTime to);

    @Query("select count(i) from Issue i where i.gameId = ?1 and i.resolvedAt >= ?2 and i.resolvedAt < ?3")
    long countResolvedBetween(Long gameId, OffsetDateTime from, OffsetDateTime to);
}
