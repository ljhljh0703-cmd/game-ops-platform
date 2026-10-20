// 게임 목록 파일 — 틀린 목록은 받지 않고, 디스코드 채널은 ID·이름 어느 쪽으로도 찾는다
package com.ljh.gameops.game;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GameCatalogTest {
    @Test
    void 채널_ID와_이름_둘_다로_찾는다() {
        var c = GameCatalog.parse("""
                {"games": [{"code": "jelly-panic", "name": "젤리 패닉", "discordChannels": ["1561709173522178068", "젤리패닉-버그제보"], "slackChannel": "C1"}]}
                """);
        assertThat(c.forDiscordChannel("1561709173522178068", "아무 이름")).map(GameSpec::code).hasValue("jelly-panic");
        assertThat(c.forDiscordChannel("123", "젤리패닉-버그제보")).map(GameSpec::code).hasValue("jelly-panic");
        assertThat(c.forDiscordChannel("123", "잡담")).isEmpty();
        // 2026-10-20 실제로 「젤리패닉-버그-제보」로 만든 채널이 「젤리패닉-버그제보」와 안 맞아 제보가 무시될 뻔했다
        assertThat(c.forDiscordChannel("123", "젤리패닉-버그-제보")).map(GameSpec::code).hasValue("jelly-panic");
        assertThat(c.forDiscordChannel("123", "젤리패닉 버그_제보")).map(GameSpec::code).hasValue("jelly-panic");
        assertThat(c.byCode("jelly-panic")).map(GameSpec::slackChannel).hasValue("C1");
    }

    @Test
    void 한_채널을_두_게임에_걸면_거절() {
        assertThatThrownBy(() -> GameCatalog.parse("""
                {"games": [{"code": "a", "name": "A", "discordChannels": ["버그"]},
                           {"code": "b", "name": "B", "discordChannels": ["#버그"]}]}
                """)).hasMessageContaining("같이 걸려");
    }

    @Test
    void 같은_코드_틀린_코드_빈_이름_빈_목록은_거절() {
        assertThatThrownBy(() -> GameCatalog.parse("{\"games\": [{\"code\": \"a\", \"name\": \"A\"}, {\"code\": \"a\", \"name\": \"B\"}]}"))
                .hasMessageContaining("두 번");
        assertThatThrownBy(() -> GameCatalog.parse("{\"games\": [{\"code\": \"Jelly Panic\", \"name\": \"A\"}]}"))
                .hasMessageContaining("영문 소문자");
        assertThatThrownBy(() -> GameCatalog.parse("{\"games\": [{\"code\": \"a\", \"name\": \" \"}]}"))
                .hasMessageContaining("이름");
        assertThatThrownBy(() -> GameCatalog.parse("{\"games\": []}")).hasMessageContaining("비어");
        assertThatThrownBy(() -> GameCatalog.parse("{games")).hasMessageContaining("JSON");
    }

    @Test
    void 기본_목록은_세_게임이고_같이_올리는_config_파일과_같다() throws Exception {
        String bundled;
        try (var in = getClass().getResourceAsStream("/games.json")) {
            bundled = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }
        assertThat(GameCatalog.parse(bundled).all()).extracting(GameSpec::code)
                .containsExactly("jelly-panic", "gemtower", "movefu");
        assertThat(java.nio.file.Files.readString(java.nio.file.Path.of("config/games.json"))).isEqualTo(bundled);
    }
}
