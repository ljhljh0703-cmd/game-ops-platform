// 운영자가 고치는 규칙 파일 — 파일이 바뀌면 다음 제보부터 새 규칙을 쓴다.
// 고친 파일에 오류가 있거나 평가 관문(RuleGate)을 못 넘으면 받지 않고 직전 규칙을 계속 쓴다(분류가 멈추지 않게).
package com.ljh.gameops.classify;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.Objects;

public class FileRuleSource implements RuleSource {
    private static final Logger log = LoggerFactory.getLogger(FileRuleSource.class);

    private final Path path;
    private final RuleGate gate;
    private volatile RuleSet current;

    // 파일 시각+크기가 그대로이고 마지막 읽기가 성공했으면 다시 읽지 않는다.
    // 실패했으면 다음 제보 때 다시 읽는다(저장 도중에 읽어 실패한 경우 다음 번에 바로잡히게).
    private String seenSignature;
    private boolean lastReadOk;
    private String loadedHash;
    private String failedHash;
    private boolean missingLogged;

    public FileRuleSource(Path path, RuleSource fallback) {
        this(path, fallback, RuleGate.NONE);
    }

    public FileRuleSource(Path path, RuleSource fallback, RuleGate gate) {
        this.path = path;
        this.gate = gate;
        this.current = fallback.current();
        reloadIfChanged();
    }

    @Override
    public RuleSet current() {
        reloadIfChanged();
        return current;
    }

    synchronized void reloadIfChanged() {
        String signature;
        try {
            BasicFileAttributes a = Files.readAttributes(path, BasicFileAttributes.class);
            signature = a.lastModifiedTime().toMillis() + ":" + a.size();
        } catch (Exception e) {
            if (!missingLogged) log.warn("규칙 파일을 읽을 수 없어 직전 규칙을 씁니다: {}", path);
            missingLogged = true;
            seenSignature = null;
            current = withError("파일 없음: " + path);
            return;
        }
        missingLogged = false;
        if (signature.equals(seenSignature) && lastReadOk) return;
        seenSignature = signature;

        byte[] bytes;
        try {
            bytes = Files.readAllBytes(path);
        } catch (Exception e) {
            lastReadOk = false;
            current = withError("파일 읽기 실패: " + e.getMessage());
            return;
        }
        String hash = sha256(bytes);
        if (hash.equals(loadedHash)) {
            lastReadOk = true;
            current = new RuleSet(current.rules(), current.source(), current.loadedAt(), null);
            return;
        }
        try {
            var rules = RuleParser.parse(new String(bytes, StandardCharsets.UTF_8));
            String rejected = gate.reject(rules);
            if (rejected != null) throw new IllegalArgumentException(rejected);
            current = new RuleSet(rules, "파일: " + path, OffsetDateTime.now(), null);
            loadedHash = hash;
            failedHash = null;
            lastReadOk = true;
            log.info("분류 규칙을 다시 읽었습니다: {} ({}개 규칙)", path, rules.size());
        } catch (Exception e) {
            lastReadOk = false;
            if (!Objects.equals(hash, failedHash)) {
                log.error("분류 규칙 파일에 오류가 있어 받지 않았습니다(직전 규칙 유지): {}", e.getMessage());
            }
            failedHash = hash;
            current = withError(e.getMessage());
        }
    }

    private RuleSet withError(String error) {
        return new RuleSet(current.rules(), current.source(), current.loadedAt(), error);
    }

    private static String sha256(byte[] b) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(b));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
