// 고친 기록 요약 — 어떤 자동 판단이 자주 틀리는지 숫자로 본다
package com.ljh.gameops.correction;

import java.util.*;
import java.util.stream.Collectors;

public final class CorrectionSummary {
    private CorrectionSummary() {}

    /** 분류가 A에서 B로 몇 번 고쳐졌나. 같은 방향이 많으면 그 키워드 규칙을 손볼 곳이다. */
    public record CategoryFix(String from, String to, long count) {}

    /** 잘못 묶여 떼어낸 제보들의 묶일 때 유사도. 대부분 기준값 바로 위라면 기준값을 올릴 근거다. */
    public record DetachStats(long count, Double minSimilarity, Double avgSimilarity, Double maxSimilarity) {}

    public record View(long total, long reclassified, long severityLowered, long severityRaised,
                       List<CategoryFix> categoryFixes, DetachStats detaches, Map<String, Long> wrongBy) {}

    public static View of(List<Correction> all) {
        List<Correction> re = all.stream().filter(c -> c.getKind() == CorrectionKind.RECLASSIFY).toList();
        List<Correction> de = all.stream().filter(c -> c.getKind() == CorrectionKind.DETACH).toList();

        Map<List<String>, Long> fixes = re.stream()
                .filter(c -> !cat(c.getBeforeValue()).equals(cat(c.getAfterValue())))
                .collect(Collectors.groupingBy(c -> List.of(cat(c.getBeforeValue()), cat(c.getAfterValue())),
                        LinkedHashMap::new, Collectors.counting()));
        List<CategoryFix> fixList = fixes.entrySet().stream()
                .map(e -> new CategoryFix(e.getKey().get(0), e.getKey().get(1), e.getValue()))
                .sorted(Comparator.comparingLong(CategoryFix::count).reversed())
                .toList();

        long lowered = re.stream().filter(c -> sev(c.getAfterValue()) < sev(c.getBeforeValue())).count();
        long raised = re.stream().filter(c -> sev(c.getAfterValue()) > sev(c.getBeforeValue())).count();

        DoubleSummaryStatistics s = de.stream().map(Correction::getSimilarity).filter(Objects::nonNull)
                .mapToDouble(Double::doubleValue).summaryStatistics();
        DetachStats ds = s.getCount() == 0 ? new DetachStats(de.size(), null, null, null)
                : new DetachStats(de.size(), round(s.getMin()), round(s.getAverage()), round(s.getMax()));

        Map<String, Long> wrongBy = all.stream().collect(Collectors.groupingBy(
                c -> c.getAutoBy() == null ? "?" : c.getAutoBy(), TreeMap::new, Collectors.counting()));

        return new View(all.size(), re.size(), lowered, raised, fixList, ds, wrongBy);
    }

    private static String cat(String label) { return label == null ? "" : label.split("/")[0]; }

    private static int sev(String label) {
        if (label == null || !label.contains("/")) return -1;
        return com.ljh.gameops.issue.Severity.valueOf(label.split("/")[1]).ordinal();
    }

    private static double round(double v) { return Math.round(v * 1000) / 1000.0; }
}
