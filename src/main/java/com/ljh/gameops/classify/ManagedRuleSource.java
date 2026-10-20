// 파일 규칙 위에 「API 로 반영한 규칙(AI 수정)」을 얹는다 — 시험을 통과해 ACTIVE 인 판이 있으면 그걸 쓰고, 없으면 파일 규칙.
// 사람이 파일을 고치면(지문이 반영 때와 달라지면) 파일 규칙이 이긴다. 사람 판단이 AI 판단 위에 있다.
package com.ljh.gameops.classify;

import org.springframework.beans.factory.ObjectProvider;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

public class ManagedRuleSource implements RuleSource {
    private final RuleSource base;
    private final ObjectProvider<RuleVersionRepository> versions;

    private record Active(Long id, List<ClassificationRule> rules, String baseHash, java.time.OffsetDateTime at) {}

    // 한 번 읽어 두고, 반영·되돌리기 때 비운다(서버 한 대 기준). null = 아직 안 읽음
    private volatile Optional<Active> cache;

    public ManagedRuleSource(RuleSource base, ObjectProvider<RuleVersionRepository> versions) {
        this.base = base;
        this.versions = versions;
    }

    /** AI 수정이 없을 때 쓰는 규칙(파일 또는 내장) */
    public RuleSet base() { return base.current(); }

    @Override
    public RuleSet current() {
        RuleSet b = base.current();
        Optional<Active> a = active();
        if (a.isPresent() && a.get().baseHash().equals(fingerprint(b.rules()))) {
            return new RuleSet(a.get().rules(), "AI 수정 #" + a.get().id() + " (파일 규칙 위)", a.get().at(), b.lastError());
        }
        return b;
    }

    public void invalidate() { cache = null; }

    private Optional<Active> active() {
        Optional<Active> c = cache;
        if (c != null) return c;
        RuleVersionRepository repo = versions.getIfAvailable();
        if (repo == null) return Optional.empty();
        try {
            c = repo.findFirstByStatusOrderByIdDesc(RuleVersion.Status.ACTIVE)
                    .map(v -> new Active(v.getId(), RuleParser.parse(v.getRulesJson()), v.getBaseHash(), v.getCreatedAt()));
        } catch (RuntimeException e) {
            // DB 를 못 읽으면 파일 규칙으로 계속 분류한다(접수가 멈추지 않게). 다음 번에 다시 읽는다
            return Optional.empty();
        }
        cache = c;
        return c;
    }

    /** 규칙 목록의 지문 — 순서·분류·심각도·키워드가 같으면 같다 */
    public static String fingerprint(List<ClassificationRule> rules) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(rules.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
