// 키워드 규칙 분류기 — 외부 호출 없이 항상 답을 낸다(기본값이자 내려가기 대상).
// 규칙은 코드 밖 파일에서 읽어 운영자가 배포 없이 고칠 수 있다.
package com.ljh.gameops.classify;

import com.ljh.gameops.issue.Category;
import com.ljh.gameops.issue.Severity;

import java.util.Locale;

public class RuleBasedClassifier implements IssueClassifier {
    private final RuleSource source;

    public RuleBasedClassifier(RuleSource source) { this.source = source; }

    /** 서버에 내장된 기본 규칙으로 만든다. */
    public RuleBasedClassifier() { this(new BundledRuleSource()); }

    @Override
    public Classification classify(String gameCode, String body) {
        String text = body.toLowerCase(Locale.ROOT) + " ";
        for (ClassificationRule rule : source.current().rules()) {
            for (String kw : rule.keywords()) {
                if (text.contains(kw)) {
                    return new Classification(rule.category(), rule.severity(), Titles.fromBody(body), "rules");
                }
            }
        }
        return new Classification(Category.OTHER, Severity.LOW, Titles.fromBody(body), "rules");
    }

    public RuleSet rules() { return source.current(); }
}
