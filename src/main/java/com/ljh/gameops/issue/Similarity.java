// 글자 2개 묶음(bigram) 자카드 유사도 — 한국어 형태소 분석 없이 띄어쓰기 차이에 강하다
package com.ljh.gameops.issue;

import java.util.HashSet;
import java.util.Set;

public final class Similarity {
    private Similarity() {}

    public static double jaccard(String a, String b) {
        return jaccard(bigrams(a), bigrams(b));
    }

    /** 새 제보의 묶음은 한 번만 만들고 열린 이슈마다 비교한다(부하 시험 ③ · docs/LOAD.md) */
    public static double jaccard(Set<String> x, Set<String> y) {
        if (x.isEmpty() || y.isEmpty()) return 0.0;
        Set<String> small = x.size() <= y.size() ? x : y, big = small == x ? y : x;
        int inter = 0;
        for (String g : small) if (big.contains(g)) inter++;
        return (double) inter / (x.size() + y.size() - inter);
    }

    public static Set<String> bigrams(String s) {
        String t = s.replace(" ", "");
        Set<String> out = new HashSet<>();
        if (t.length() == 1) out.add(t);
        for (int i = 0; i + 2 <= t.length(); i++) out.add(t.substring(i, i + 2));
        return out;
    }
}
