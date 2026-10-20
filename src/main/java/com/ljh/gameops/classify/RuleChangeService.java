// 운영 판단 자동 고치기(결정 26) — AI 가 낸 분류 규칙 수정안을 서버가 다시 시험하고, 통과하면 바로 반영한다.
// 통과 조건: ① 평가 관문(dev 95% 이상 · 회귀 문장 전부) ② dev 정확도가 지금보다 떨어지지 않음
// ③ 결제·크래시 키워드를 빼거나 그 심각도를 낮추지 않음(돈·강제 종료는 사람이 파일로) ④ 실제로 바뀐 게 있음.
// 반영하면 슬랙에 「바꿨어요 · 되돌리기」 카드를 보낸다. 사람이 파일을 고치면 파일이 이긴다(ManagedRuleSource).
package com.ljh.gameops.classify;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ljh.gameops.audit.AuditLog;
import com.ljh.gameops.audit.AuditLogRepository;
import com.ljh.gameops.common.ConflictException;
import com.ljh.gameops.common.NotFoundException;
import com.ljh.gameops.issue.Category;
import com.ljh.gameops.issue.Severity;
import com.ljh.gameops.notify.NotificationType;
import com.ljh.gameops.notify.OutboxService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

@Service
public class RuleChangeService {
    static final Set<Category> PROTECTED = EnumSet.of(Category.PAYMENT, Category.CRASH);
    private static final Map<Category, String> CAT = Map.of(
            Category.CRASH, "크래시", Category.BUG, "버그", Category.PERFORMANCE, "성능", Category.BALANCE, "밸런스",
            Category.PAYMENT, "결제", Category.UX, "불편", Category.OTHER, "기타");
    private static final Map<Severity, String> SEV = Map.of(
            Severity.CRITICAL, "긴급", Severity.HIGH, "높음", Severity.MEDIUM, "보통", Severity.LOW, "낮음");

    private final ManagedRuleSource source;
    private final RuleGate gate;
    private final RuleVersionRepository versions;
    private final AuditLogRepository audit;
    private final OutboxService outbox;
    private final ObjectMapper mapper;
    private final org.springframework.context.ApplicationEventPublisher events;

    public RuleChangeService(ManagedRuleSource source, RuleGate gate, RuleVersionRepository versions, AuditLogRepository audit,
                             OutboxService outbox, ObjectMapper mapper, org.springframework.context.ApplicationEventPublisher events) {
        this.events = events;
        this.source = source;
        this.gate = gate;
        this.versions = versions;
        this.audit = audit;
        this.outbox = outbox;
        this.mapper = mapper;
    }

    /** applied 면 versionId 가 있고, 아니면 rejected 에 이유가 있다. */
    public record Result(boolean applied, Long versionId, double devBefore, double devAfter, int regressionTotal,
                         List<String> changes, List<String> rejected) {}

    @Transactional
    public Result apply(String rulesJson, String actor, String reason) {
        List<ClassificationRule> candidate = RuleParser.parse(rulesJson); // 형식이 틀리면 IllegalArgumentException(400)
        List<ClassificationRule> current = source.current().rules();
        if (!(gate instanceof EvalRuleGate eval)) {
            return new Result(false, null, 0, 0, 0, List.of(), List.of("서버에 평가 묶음이 없어 자동 반영할 수 없어요. 파일로 고쳐 주세요"));
        }
        double before = eval.devAccuracy(current), after = eval.devAccuracy(candidate);
        List<String> changes = diff(current, candidate);
        List<String> why = new ArrayList<>();
        if (changes.isEmpty()) why.add("지금 규칙과 같아요");
        String gateWhy = eval.reject(candidate);
        if (gateWhy != null) why.add(gateWhy);
        if (after + 1e-9 < before) why.add("dev 정확도가 떨어져요 %.0f%% → %.0f%%".formatted(before * 100, after * 100));
        why.addAll(protectedViolations(current, candidate));
        if (!why.isEmpty()) return new Result(false, null, before, after, eval.regressionCount(), changes, why);

        String json;
        try {
            json = mapper.writeValueAsString(Map.of("rules", candidate));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        versions.findFirstByStatusOrderByIdDesc(RuleVersion.Status.ACTIVE).ifPresent(RuleVersion::supersede);
        RuleVersion v = versions.saveAndFlush(new RuleVersion(json, ManagedRuleSource.fingerprint(source.base().rules()), actor,
                reason, String.join("\n", changes), before, after, eval.regressionCount()));
        audit.save(new AuditLog(actor, "RULES_APPLY", "rules:" + v.getId(), String.join(" / ", changes) + (reason == null ? "" : " · " + reason)));
        outbox.enqueue(NotificationType.RULES_CHANGED, Map.of("versionId", v.getId()));
        source.invalidate();
        return new Result(true, v.getId(), before, after, eval.regressionCount(), changes, List.of());
    }

    @Transactional
    public RuleVersion revert(long versionId, String actor) {
        RuleVersion v = versions.findById(versionId).orElseThrow(() -> new NotFoundException("규칙 판 없음: " + versionId));
        if (v.getStatus() != RuleVersion.Status.ACTIVE) {
            throw new ConflictException(v.getStatus() == RuleVersion.Status.REVERTED ? "이미 되돌렸어요" : "더 새 수정이 들어와 있어요. 최신 판을 되돌려 주세요");
        }
        v.revert(actor);
        versions.saveAndFlush(v);
        audit.save(new AuditLog(actor, "RULES_REVERT", "rules:" + versionId, "파일 규칙으로 돌아감"));
        source.invalidate();
        events.publishEvent(new com.ljh.gameops.notify.RulesChanged(versionId, actor, "↩️ 되돌림", null, null));
        return v;
    }

    @Transactional(readOnly = true)
    public List<RuleVersion> list() { return versions.findTop50ByOrderByIdDesc(); }

    @Transactional(readOnly = true)
    public RuleVersion get(long id) { return versions.findById(id).orElseThrow(() -> new NotFoundException("규칙 판 없음: " + id)); }

    // ── 비교 ──

    static String label(Category c, Severity s) { return CAT.get(c) + "/" + SEV.get(s); }

    /** 「분류/심각도」별 키워드 더한 것·뺀 것, 순서·규칙 수가 바뀌었으면 그것도. */
    static List<String> diff(List<ClassificationRule> before, List<ClassificationRule> after) {
        Map<String, LinkedHashSet<String>> b = byLabel(before), a = byLabel(after);
        LinkedHashSet<String> labels = new LinkedHashSet<>(b.keySet());
        labels.addAll(a.keySet());
        List<String> out = new ArrayList<>();
        for (String l : labels) {
            var x = b.getOrDefault(l, new LinkedHashSet<>());
            var y = a.getOrDefault(l, new LinkedHashSet<>());
            List<String> added = y.stream().filter(k -> !x.contains(k)).toList();
            List<String> removed = x.stream().filter(k -> !y.contains(k)).toList();
            if (!added.isEmpty()) out.add(l + " + " + quote(added));
            if (!removed.isEmpty()) out.add(l + " − " + quote(removed));
        }
        List<String> orderB = before.stream().map(r -> label(r.category(), r.severity())).toList();
        List<String> orderA = after.stream().map(r -> label(r.category(), r.severity())).toList();
        if (!orderB.equals(orderA)) out.add("규칙 순서·개수 바뀜 " + before.size() + "개 → " + after.size() + "개");
        return out;
    }

    private static Map<String, LinkedHashSet<String>> byLabel(List<ClassificationRule> rules) {
        Map<String, LinkedHashSet<String>> m = new LinkedHashMap<>();
        for (ClassificationRule r : rules) m.computeIfAbsent(label(r.category(), r.severity()), k -> new LinkedHashSet<>()).addAll(r.keywords());
        return m;
    }

    private static String quote(List<String> ks) {
        StringBuilder s = new StringBuilder();
        for (String k : ks) s.append(s.isEmpty() ? "" : " ").append("「").append(k).append("」");
        return s.toString();
    }

    /** 결제·크래시 키워드는 빼지 않고(다른 분류로 옮기는 것도 뺀 것), 그 규칙의 가장 낮은 심각도를 낮추지 않는다. */
    static List<String> protectedViolations(List<ClassificationRule> before, List<ClassificationRule> after) {
        List<String> out = new ArrayList<>();
        for (Category c : PROTECTED) {
            Set<String> kb = keywords(before, c), ka = keywords(after, c);
            List<String> lost = kb.stream().filter(k -> !ka.contains(k)).toList();
            if (!lost.isEmpty()) out.add(CAT.get(c) + " 키워드를 빼는 수정은 사람이 파일로 해요: " + quote(lost));
            Optional<Severity> sb = minSeverity(before, c), sa = minSeverity(after, c);
            if (sb.isPresent() && sa.isPresent() && sa.get().ordinal() < sb.get().ordinal()) {
                out.add(CAT.get(c) + " 심각도를 낮추는 수정은 사람이 파일로 해요: " + SEV.get(sb.get()) + " → " + SEV.get(sa.get()));
            }
        }
        return out;
    }

    private static Set<String> keywords(List<ClassificationRule> rules, Category c) {
        Set<String> s = new HashSet<>();
        for (ClassificationRule r : rules) if (r.category() == c) s.addAll(r.keywords());
        return s;
    }

    private static Optional<Severity> minSeverity(List<ClassificationRule> rules, Category c) {
        return rules.stream().filter(r -> r.category() == c).map(ClassificationRule::severity).min(Comparator.naturalOrder());
    }
}
