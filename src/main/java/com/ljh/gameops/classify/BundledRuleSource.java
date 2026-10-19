// 서버에 함께 들어 있는 기본 규칙
package com.ljh.gameops.classify;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;

public class BundledRuleSource implements RuleSource {
    private final RuleSet set;

    public BundledRuleSource() {
        try (InputStream in = BundledRuleSource.class.getResourceAsStream("/classifier-rules.json")) {
            if (in == null) throw new IllegalStateException("기본 규칙 파일이 없습니다");
            this.set = new RuleSet(RuleParser.parse(new String(in.readAllBytes(), StandardCharsets.UTF_8)),
                    "기본값(서버 내장)", OffsetDateTime.now(), null);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public RuleSet current() { return set; }
}
