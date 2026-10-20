// 규칙 판 저장소
package com.ljh.gameops.classify;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface RuleVersionRepository extends JpaRepository<RuleVersion, Long> {
    Optional<RuleVersion> findFirstByStatusOrderByIdDesc(RuleVersion.Status status);

    List<RuleVersion> findTop50ByOrderByIdDesc();
}
