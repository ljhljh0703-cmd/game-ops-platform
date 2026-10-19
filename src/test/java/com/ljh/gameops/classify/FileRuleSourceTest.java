package com.ljh.gameops.classify;

import com.ljh.gameops.issue.Category;
import com.ljh.gameops.issue.Severity;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FileRuleSourceTest {
    @TempDir Path dir;

    private void write(Path f, String json, long epochSec) throws Exception {
        Files.writeString(f, json);
        Files.setLastModifiedTime(f, FileTime.from(Instant.ofEpochSecond(epochSec)));
    }

    @Test
    void 파일을_고치면_다음_분류부터_새_규칙을_쓴다() throws Exception {
        Path f = dir.resolve("rules.json");
        write(f, "{\"rules\":[{\"category\":\"BUG\",\"severity\":\"MEDIUM\",\"keywords\":[\"버그\"]}]}", 1000);
        var c = new RuleBasedClassifier(new FileRuleSource(f, new BundledRuleSource()));
        assertThat(c.classify("g", "젤리가 사라지는 현상").category()).isEqualTo(Category.OTHER);

        write(f, "{\"rules\":[{\"category\":\"BUG\",\"severity\":\"HIGH\",\"keywords\":[\"사라지\"]}]}", 2000);
        var r = c.classify("g", "젤리가 사라지는 현상");
        assertThat(r.category()).isEqualTo(Category.BUG);
        assertThat(r.severity()).isEqualTo(Severity.HIGH);
        assertThat(c.rules().source()).startsWith("파일:");
    }

    @Test
    void 틀린_파일은_받지_않고_직전_규칙을_계속_쓴다() throws Exception {
        Path f = dir.resolve("rules.json");
        write(f, "{\"rules\":[{\"category\":\"BUG\",\"severity\":\"HIGH\",\"keywords\":[\"사라지\"]}]}", 1000);
        var c = new RuleBasedClassifier(new FileRuleSource(f, new BundledRuleSource()));

        write(f, "{\"rules\":[{\"category\":\"없는분류\",\"severity\":\"HIGH\",\"keywords\":[\"x\"]}]}", 2000);
        assertThat(c.classify("g", "젤리가 사라지는 현상").category()).isEqualTo(Category.BUG);
        assertThat(c.rules().lastError()).contains("category");

        // 고쳐서 다시 저장하면 오류 표시가 사라진다
        write(f, "{\"rules\":[{\"category\":\"UX\",\"severity\":\"LOW\",\"keywords\":[\"사라지\"]}]}", 3000);
        assertThat(c.classify("g", "젤리가 사라지는 현상").category()).isEqualTo(Category.UX);
        assertThat(c.rules().lastError()).isNull();
    }

    @Test
    void 파일이_없으면_내장_규칙으로_돈다() {
        var c = new RuleBasedClassifier(new FileRuleSource(dir.resolve("missing.json"), new BundledRuleSource()));
        assertThat(c.classify("g", "결제했는데 안 들어와요").category()).isEqualTo(Category.PAYMENT);
        assertThat(c.rules().lastError()).contains("파일 없음");
    }

    @Test
    void 검사_규칙() {
        assertThatThrownBy(() -> RuleParser.parse("{")).hasMessageContaining("JSON");
        assertThatThrownBy(() -> RuleParser.parse("{\"rules\":[]}")).hasMessageContaining("비어");
        assertThatThrownBy(() -> RuleParser.parse("{\"rules\":[{\"category\":\"BUG\",\"severity\":\"아주높음\",\"keywords\":[\"x\"]}]}"))
                .hasMessageContaining("severity");
        assertThatThrownBy(() -> RuleParser.parse("{\"rules\":[{\"category\":\"BUG\",\"severity\":\"LOW\",\"keywords\":[\" \"]}]}"))
                .hasMessageContaining("빈 키워드");
        // 대문자 키워드도 소문자로 맞춰 비교한다
        assertThat(RuleParser.parse("{\"rules\":[{\"category\":\"BUG\",\"severity\":\"LOW\",\"keywords\":[\"FPS\"]}]}")
                .get(0).keywords()).containsExactly("fps");
    }
}
