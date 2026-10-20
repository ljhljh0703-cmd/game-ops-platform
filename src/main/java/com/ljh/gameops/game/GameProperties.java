// 게임 목록 파일 위치 — 비우면 서버에 같이 넣은 기본 목록(games.json)을 쓴다
package com.ljh.gameops.game;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "gameops.games")
public record GameProperties(String file) {}
