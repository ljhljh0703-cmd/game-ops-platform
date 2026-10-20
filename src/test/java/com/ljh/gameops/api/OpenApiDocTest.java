// API 명세 파일(docs/api/openapi.json)이 코드와 같은지 — 다르면 실패하고, -DupdateOpenApi=true 로 돌리면 새로 쓴다
package com.ljh.gameops.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.ljh.gameops.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class OpenApiDocTest extends IntegrationTest {
    private static final Path FILE = Path.of("docs/api/openapi.json");
    @Autowired TestRestTemplate http;

    @Test
    void 명세_파일이_코드와_같다() throws Exception {
        ObjectMapper m = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT)
                .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);
        JsonNode live = m.readTree(http.getForObject("/v3/api-docs", String.class));
        String pretty = m.writeValueAsString(m.treeToValue(live, Object.class)) + "\n";
        assertThat(live.path("paths").has("/api/games")).isTrue();
        assertThat(live.path("paths").has("/slack/interactions")).isFalse();
        if (Boolean.getBoolean("updateOpenApi") || !Files.exists(FILE)) {
            Files.createDirectories(FILE.getParent());
            Files.writeString(FILE, pretty, StandardCharsets.UTF_8);
        }
        assertThat(Files.readString(FILE, StandardCharsets.UTF_8))
                .as("API 가 바뀌었는데 docs/api/openapi.json 이 옛것. ./gradlew test -DupdateOpenApi=true 로 새로 쓰기")
                .isEqualTo(pretty);
    }
}
