// 게임 목록을 읽고, 켤 때 DB 의 game 표를 목록에 맞춘다(새 게임 추가·이름 갱신, 지우지는 않음 — 지난 제보 보존)
package com.ljh.gameops.game;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

@Configuration
public class GameConfig {
    private static final Logger log = LoggerFactory.getLogger(GameConfig.class);

    @Bean
    GameCatalog gameCatalog(GameProperties props, JdbcTemplate jdbc) throws Exception {
        String json;
        String source;
        if (props.file() != null && !props.file().isBlank()) {
            Path p = Path.of(props.file());
            if (!Files.isReadable(p)) throw new IllegalStateException("게임 목록 파일을 읽을 수 없습니다: " + p);
            json = Files.readString(p, StandardCharsets.UTF_8);
            source = p.toString();
        } else {
            json = new String(new ClassPathResource("games.json").getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            source = "기본 목록(games.json)";
        }
        GameCatalog catalog;
        try {
            catalog = GameCatalog.parse(json);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("게임 목록 오류(" + source + "): " + e.getMessage(), e);
        }
        for (GameSpec g : catalog.all()) {
            jdbc.update("INSERT INTO game (code, name) VALUES (?, ?) ON CONFLICT (code) DO UPDATE SET name = EXCLUDED.name",
                    g.code(), g.name());
        }
        log.info("게임 {}개를 읽었습니다: {} ({})", catalog.all().size(),
                catalog.all().stream().map(GameSpec::name).toList(), source);
        return catalog;
    }
}
