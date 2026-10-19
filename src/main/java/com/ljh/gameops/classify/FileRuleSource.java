// 운영자가 고치는 규칙 파일 — 파일이 바뀌면 다음 제보부터 새 규칙을 쓴다.
// 고친 파일에 오류가 있으면 받지 않고 직전 규칙을 계속 쓴다(분류가 멈추지 않게).
package com.ljh.gameops.classify;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.OffsetDateTime;

public class FileRuleSource implements RuleSource {
    private static final Logger log = LoggerFactory.getLogger(FileRuleSource.class);

    private final Path path;
    private final RuleSource fallback;
    private volatile RuleSet current;
    private volatile FileTime seenModified;

    public FileRuleSource(Path path, RuleSource fallback) {
        this.path = path;
        this.fallback = fallback;
        this.current = fallback.current();
        reloadIfChanged();
    }

    @Override
    public RuleSet current() {
        reloadIfChanged();
        return current;
    }

    synchronized void reloadIfChanged() {
        FileTime modified;
        try {
            modified = Files.getLastModifiedTime(path);
        } catch (Exception e) {
            if (seenModified != null || current.lastError() == null) {
                log.warn("규칙 파일을 읽을 수 없어 직전 규칙을 씁니다: {}", path);
            }
            current = new RuleSet(current.rules(), current.source(), current.loadedAt(), "파일 없음: " + path);
            return;
        }
        if (modified.equals(seenModified)) return;
        seenModified = modified;
        try {
            var rules = RuleParser.parse(Files.readString(path, StandardCharsets.UTF_8));
            current = new RuleSet(rules, "파일: " + path, OffsetDateTime.now(), null);
            log.info("분류 규칙을 다시 읽었습니다: {} ({}개 규칙)", path, rules.size());
        } catch (Exception e) {
            current = new RuleSet(current.rules(), current.source(), current.loadedAt(), e.getMessage());
            log.error("분류 규칙 파일에 오류가 있어 받지 않았습니다(직전 규칙 유지): {}", e.getMessage());
        }
    }
}
