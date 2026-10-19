// 글자 2개 묶음(bigram) 자카드 유사도 — 한국어 형태소 분석 없이 띄어쓰기 차이에 강하다
package com.ljh.gameops.issue;

import java.util.HashSet;
import java.util.Set;

public final class Similarity {
    private Similarity() {}

    public static double jaccard(String a, String b) {
        Set<String> x = bigrams(a);
        Set<String> y = bigrams(b);
        if (x.isEmpty() && y.isEmpty()) return 1.0;
        if (x.isEmpty() || y.isEmpty()) return 0.0;
        Set<String> inter = new HashSet<>(x);
        inter.retainAll(y);
        Set<String> union = new HashSet<>(x);
        union.addAll(y);
        return (double) inter.size() / union.size();
    }

    static Set<String> bigrams(String s) {
        String t = s.replace(" ", "");
        Set<String> out = new HashSet<>();
        if (t.length() == 1) out.add(t);
        for (int i = 0; i + 2 <= t.length(); i++) out.add(t.substring(i, i + 2));
        return out;
    }
}
