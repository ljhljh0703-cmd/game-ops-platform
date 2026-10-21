// 운영하는 게임 목록 — games.json 을 읽어 검사하고, 디스코드 채널 → 게임, 게임 → 슬랙 채널을 찾아 준다.
// 하나라도 틀리면 서버를 켜지 않는다(엉뚱한 게임으로 제보가 쌓이는 것보다 켜지 않는 편이 낫다).
package com.ljh.gameops.game;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.*;
import java.util.regex.Pattern;

public final class GameCatalog {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Pattern CODE = Pattern.compile("[a-z0-9][a-z0-9-]{0,39}");

    private final List<GameSpec> games;
    private final Map<String, GameSpec> byCode = new LinkedHashMap<>();
    private final Map<String, GameSpec> byDiscordChannel = new HashMap<>();

    public GameCatalog(List<GameSpec> games) {
        this.games = List.copyOf(games);
        for (GameSpec g : this.games) {
            if (g.code() == null || !CODE.matcher(g.code()).matches()) {
                throw new IllegalArgumentException("게임 코드는 영문 소문자·숫자·- 로 40자 이내: " + g.code());
            }
            if (g.name() == null || g.name().isBlank()) throw new IllegalArgumentException("게임 이름이 비어 있습니다: " + g.code());
            if (g.name().length() > 100) throw new IllegalArgumentException("게임 이름은 100자 이내: " + g.code());
            if (byCode.put(g.code(), g) != null) throw new IllegalArgumentException("같은 게임 코드가 두 번 있습니다: " + g.code());
            for (String ch : g.discordChannels()) {
                String key = normalize(ch);
                if (key.isEmpty()) continue;
                GameSpec prev = byDiscordChannel.put(key, g);
                if (prev != null && prev != g) {
                    throw new IllegalArgumentException("디스코드 채널 " + ch + " 이(가) " + prev.code() + " 와 " + g.code() + " 에 같이 걸려 있습니다");
                }
            }
        }
    }

    public static GameCatalog empty() { return new GameCatalog(List.of()); }

    public static GameCatalog parse(String json) {
        JsonNode root;
        try {
            root = MAPPER.readTree(json);
        } catch (Exception e) {
            throw new IllegalArgumentException("games 파일 JSON 형식 오류: " + e.getMessage());
        }
        JsonNode arr = root.path("games");
        if (!arr.isArray() || arr.isEmpty()) throw new IllegalArgumentException("games 배열이 비어 있거나 없습니다");
        List<GameSpec> out = new ArrayList<>();
        for (JsonNode g : arr) {
            List<String> channels = new ArrayList<>();
            g.path("discordChannels").forEach(c -> channels.add(c.asText()));
            out.add(new GameSpec(text(g, "code"), text(g, "name"), channels,
                    text(g, "slackChannel"), text(g, "slackUrgentChannel"), text(g, "botName"), text(g, "icon"), text(g, "repo"),
                    g.path("qa").asBoolean(false), jiraKey(text(g, "jiraProject"))));
        }
        return new GameCatalog(out);
    }

    /** Jira 프로젝트 키는 영문 대문자로 시작하는 짧은 키(예: GEM). 형식이 틀리면 켜기 전에 알린다 */
    static String jiraKey(String k) {
        if (k == null || k.isBlank()) return null;
        String t = k.strip();
        if (!t.matches("[A-Z][A-Z0-9_]{0,19}")) throw new IllegalArgumentException("jiraProject 형식 오류(영문 대문자 키, 예: GEM): " + t);
        return t;
    }

    private static String text(JsonNode n, String field) {
        JsonNode v = n.get(field);
        return v == null || v.isNull() || v.asText().isBlank() ? null : v.asText().trim();
    }

    /** 채널 ID 로 먼저, 없으면 채널 이름으로 찾는다(이름은 #·대소문자·띄어쓰기·-·_ 무시: 「보석타워-버그-제보」=「보석타워-버그제보」). */
    public Optional<GameSpec> forDiscordChannel(String channelId, String channelName) {
        GameSpec g = channelId == null ? null : byDiscordChannel.get(normalize(channelId));
        if (g == null && channelName != null) g = byDiscordChannel.get(normalize(channelName));
        return Optional.ofNullable(g);
    }

    public Optional<GameSpec> byCode(String code) {
        return code == null ? Optional.empty() : Optional.ofNullable(byCode.get(code));
    }

    public List<GameSpec> all() { return games; }

    static String normalize(String channel) {
        String s = channel == null ? "" : channel.trim();
        if (s.startsWith("#")) s = s.substring(1);
        // 디스코드는 채널 이름의 띄어쓰기를 - 로 바꾸므로, 사람이 「버그 제보」「버그-제보」「버그제보」 어느 쪽으로 만들어도 같게 본다
        return s.toLowerCase(Locale.ROOT).replaceAll("[\\s_-]", "");
    }
}
