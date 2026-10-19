// 앞 분류기가 실패하면 규칙 분류기로 내려간다 — 제보 접수가 분류 때문에 멈추지 않게
package com.ljh.gameops.classify;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class FallbackClassifier implements IssueClassifier {
    private static final Logger log = LoggerFactory.getLogger(FallbackClassifier.class);

    private final IssueClassifier primary;
    private final IssueClassifier fallback;

    public FallbackClassifier(IssueClassifier primary, IssueClassifier fallback) {
        this.primary = primary;
        this.fallback = fallback;
    }

    @Override
    public Classification classify(String gameCode, String body) {
        try {
            return primary.classify(gameCode, body);
        } catch (RuntimeException e) {
            log.warn("주 분류기 실패, 규칙 분류로 내려감: {}", e.getMessage());
            return fallback.classify(gameCode, body);
        }
    }
}
