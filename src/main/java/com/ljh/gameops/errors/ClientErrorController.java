// 게임이 오류를 보내는 곳 — 브라우저 게임에서 바로 부른다(sendBeacon·fetch). 공개 주소로 열 수 있게 크기·횟수를 막는다.
// games.json 의 errorKey 가 있으면 ?key= 가 맞아야 받는다(브라우저 코드에 들어가는 값이라 비밀은 아니고, 아무나 보내는 걸 줄이는 정도)
package com.ljh.gameops.errors;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ljh.gameops.game.GameCatalog;
import com.ljh.gameops.game.GameRepository;
import com.ljh.gameops.game.GameSpec;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@Tag(name = "게임 오류", description = "게임에 넣은 코드 조각이 보내는 오류 기록")
public class ClientErrorController {
    static final int BODY_MAX = 16_000;

    private final ClientErrors errors;
    private final GameCatalog games;
    private final GameRepository gameRepo;
    private final ObjectMapper mapper;

    public ClientErrorController(ClientErrors errors, GameCatalog games, GameRepository gameRepo, ObjectMapper mapper) {
        this.errors = errors;
        this.games = games;
        this.gameRepo = gameRepo;
        this.mapper = mapper;
    }

    @CrossOrigin(origins = "*", methods = {RequestMethod.POST, RequestMethod.OPTIONS})
    @PostMapping(value = "/api/games/{code}/errors", consumes = "*/*")
    @Operation(summary = "게임 오류 보내기", description = "본문 JSON {message, stack, version, device, context}. text/plain 으로 보내도 된다(sendBeacon). 16KB 까지. 같은 오류는 횟수만 늘린다")
    public ResponseEntity<?> receive(@PathVariable String code, @RequestParam(required = false) String key, @RequestBody(required = false) String body) {
        GameSpec g = games.byCode(code).orElse(null);
        if (g == null) return ResponseEntity.status(404).body(Map.of("error", "없는 게임"));
        if (g.errorKey() != null && !g.errorKey().equals(key)) return ResponseEntity.status(403).body(Map.of("error", "키가 달라요"));
        if (body == null || body.isBlank() || body.length() > BODY_MAX) return ResponseEntity.badRequest().body(Map.of("error", "본문이 비었거나 너무 커요"));
        JsonNode n;
        try {
            n = mapper.readTree(body);
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", "JSON 형식 오류"));
        }
        long gameId = gameRepo.findByCode(code).orElseThrow().getId();
        var id = errors.record(gameId, new ClientErrors.Incoming(text(n, "message"), text(n, "stack"), text(n, "version"),
                text(n, "device"), text(n, "context")));
        return ResponseEntity.accepted().body(Map.of("stored", id.isPresent()));
    }

    private static String text(JsonNode n, String f) {
        JsonNode v = n.get(f);
        return v == null || v.isNull() ? null : v.asText();
    }
}
