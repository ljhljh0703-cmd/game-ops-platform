// 이슈-제보 연결 저장소
package com.ljh.gameops.issue;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface IssueReportRepository extends JpaRepository<IssueReport, IssueReport.Key> {
    List<IssueReport> findByIssueId(Long issueId);
    Optional<IssueReport> findByReportId(Long reportId);
}
