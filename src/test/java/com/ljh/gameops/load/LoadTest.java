// 부하 시험 — ./gradlew loadTest (평소 test 에서는 빠진다). 실제 PostgreSQL(테스트 컨테이너) 위에 서버를 띄우고
// 제보 접수 API 를 동시에 두드려 처리량·응답 시간·끝난 뒤 숫자가 맞는지 본다. 결과: build/load-report.md
// 세 장면: ① 보통(여러 게임·여러 문제) ② 한 이슈에 몰림(같은 버그에 제보 폭주 = 이슈 한 줄 잠금 경합) ③ 열린 이슈가 많이 쌓였을 때
// 시험 기계와 서버가 같은 컴퓨터라 숫자는 「이 기계에서」다. AI 는 끈 상태(키 없음) — AI 판정·번역은 DB 거래 밖이라 처리량이 아니라 한 건 시간에 더해진다.
package com.ljh.gameops.load;

import com.ljh.gameops.IntegrationTest;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.web.server.LocalServerPort;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntFunction;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("load")
class LoadTest extends IntegrationTest {
    @LocalServerPort int port;

    static final String[] GAMES = {"jelly-panic", "gemtower", "movefu"};
    static final String[] SYMPTOMS = {"게임이 튕겨요", "결제했는데 보석이 안 들어왔어요", "렉이 너무 심해요", "버튼이 안 눌려요",
            "보스가 너무 쎄요", "세이브가 날아갔어요", "로딩이 길어요", "화면 글씨가 작아요", "보상이 안 들어와요", "검은 화면에서 멈춰요"};
    static final String[] PLACES = {"상점", "보스전", "튜토리얼", "멀티 대전", "이벤트 화면", "출석 보상", "설정 화면", "랭킹전"};

    record Result(String name, int n, int conc, long wallMs, List<Long> lat, int errors, Map<Integer, Integer> codes) {
        double rps() { return n * 1000.0 / wallMs; }
        long p(double q) { List<Long> s = lat.stream().sorted().toList(); return s.isEmpty() ? 0 : s.get(Math.min(s.size() - 1, (int) Math.ceil(q * s.size()) - 1)); }
    }

    final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
            .executor(Executors.newVirtualThreadPerTaskExecutor()).build();

    Result run(String name, int n, int conc, IntFunction<String> json) throws Exception {
        Semaphore gate = new Semaphore(conc);
        List<Long> lat = Collections.synchronizedList(new ArrayList<>());
        Map<Integer, Integer> codes = new ConcurrentHashMap<>();
        AtomicInteger errors = new AtomicInteger();
        long t0 = System.nanoTime();
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < n; i++) {
                int k = i;
                gate.acquire();
                pool.submit(() -> {
                    long s = System.nanoTime();
                    try {
                        var res = http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/reports"))
                                .timeout(Duration.ofSeconds(30)).header("Content-Type", "application/json")
                                .POST(HttpRequest.BodyPublishers.ofString(json.apply(k))).build(), HttpResponse.BodyHandlers.discarding());
                        codes.merge(res.statusCode(), 1, Integer::sum);
                        if (res.statusCode() / 100 != 2) errors.incrementAndGet();
                    } catch (Exception e) {
                        errors.incrementAndGet();
                        codes.merge(-1, 1, Integer::sum);
                    } finally {
                        lat.add((System.nanoTime() - s) / 1_000_000);
                        gate.release();
                    }
                });
            }
        }
        return new Result(name, n, conc, (System.nanoTime() - t0) / 1_000_000, lat, errors.get(), new TreeMap<>(codes));
    }

    static String report(String game, String author, String body) {
        return "{\"game\":\"" + game + "\",\"authorId\":\"" + author + "\",\"body\":\"" + body + "\"}";
    }

    /** 서로 묶이지 않을 만큼 다른 글(무작위 한글 낱말) */
    static String noise(Random r) {
        StringBuilder s = new StringBuilder();
        for (int w = 0; w < 5; w++) {
            for (int c = 0; c < 3; c++) s.append((char) (0xAC00 + r.nextInt(11172)));
            s.append(' ');
        }
        return s.append("버그").toString();
    }

    void warmup() throws Exception {
        run("warmup", 200, 20, i -> report(GAMES[i % 3], "w" + i, PLACES[i % PLACES.length] + "에서 " + SYMPTOMS[i % SYMPTOMS.length]));
        cleanTablesNow();
    }

    void cleanTablesNow() {
        jdbc.execute("TRUNCATE notification_outbox, audit_log, issue_report, issue, report RESTART IDENTITY CASCADE");
    }

    /** 끝난 뒤 숫자가 맞는가: 제보 수 = 이슈에 묶인 제보 수 = 이슈 제보 수 합 */
    String consistency(int expectedReports) {
        Integer reports = jdbc.queryForObject("SELECT count(*) FROM report", Integer.class);
        Integer links = jdbc.queryForObject("SELECT count(*) FROM issue_report", Integer.class);
        Integer sum = jdbc.queryForObject("SELECT coalesce(sum(report_count),0) FROM issue", Integer.class);
        Integer issues = jdbc.queryForObject("SELECT count(*) FROM issue", Integer.class);
        Integer dupKeys = jdbc.queryForObject("SELECT count(*) FROM (SELECT game_id, normalized_key FROM issue WHERE status IN ('OPEN','IN_PROGRESS') GROUP BY 1,2 HAVING count(*) > 1) x", Integer.class);
        assertThat(reports).isEqualTo(expectedReports);
        assertThat(links).isEqualTo(expectedReports);
        assertThat(sum).isEqualTo(expectedReports);
        assertThat(dupKeys).isZero();
        return "제보 %d · 묶인 제보 %d · 이슈 제보 수 합 %d · 이슈 %d · 같은 글로 열린 이슈 두 개 %d".formatted(reports, links, sum, issues, dupKeys);
    }

    @Test
    void 부하() throws Exception {
        warmup();
        StringBuilder md = new StringBuilder("# 부하 시험 결과\n\n");
        md.append("기계: ").append(Runtime.getRuntime().availableProcessors()).append("코어 · 최대 메모리 ")
                .append(Runtime.getRuntime().maxMemory() / (1 << 20)).append("MB · 서버·DB·시험 도구가 한 기계. AI 끔.\n\n");
        md.append("| 장면 | 요청 | 동시 | 걸린 시간 | 초당 | 중앙값 | 95% | 99% | 최대 | 실패 | 응답 코드 |\n|---|---|---|---|---|---|---|---|---|---|---|\n");
        List<String> checks = new ArrayList<>();

        // ① 보통: 3게임 × 장소 8 × 증상 10 (서로 다른 문제 240가지에 제보가 고르게)
        Random rnd = new Random(1);
        int n1 = 3000;
        var r1 = run("① 보통", n1, 50, i -> report(GAMES[i % 3], "p" + rnd.nextInt(5000),
                PLACES[(i / 3) % PLACES.length] + "에서 " + SYMPTOMS[(i / 24) % SYMPTOMS.length] + (i % 7 == 0 ? " ㅠㅠ" : "")));
        row(md, r1);
        checks.add("① " + consistency(n1));
        cleanTablesNow();

        // ② 한 이슈에 몰림: 같은 버그 제보 1,000건이 동시에(인기 게임 점검 직후 같은 상황)
        int n2 = 1000;
        var r2 = run("② 한 이슈에 몰림", n2, 50, i -> report("gemtower", "h" + i, "5웨이브에서 게임이 튕겨요" + (i % 3 == 0 ? "ㅠㅠ" : i % 3 == 1 ? "!!" : "")));
        row(md, r2);
        checks.add("② " + consistency(n2));
        Integer hot = jdbc.queryForObject("SELECT max(report_count) FROM issue", Integer.class);
        checks.add("② 가장 큰 이슈 제보 수 " + hot + " (전부 한 이슈에 붙어야 1000)");
        assertThat(hot).isEqualTo(n2);
        cleanTablesNow();

        // ③ 열린 이슈가 쌓였을 때: 서로 다른 문제 2,000개를 먼저 열어 두고, 새 제보 500건
        Random r3n = new Random(3);
        var pre = run("③ 준비: 열린 이슈 2,000개", 2000, 50, i -> report("jelly-panic", "b" + i, noise(r3n)));
        Integer open = jdbc.queryForObject("SELECT count(*) FROM issue", Integer.class);
        var r3 = run("③ 열린 이슈 " + open + "개 위에 새 제보", 500, 50, i -> report("jelly-panic", "c" + i, noise(r3n)));
        row(md, pre);
        row(md, r3);
        checks.add("③ " + consistency(2500));

        md.append("\n끝난 뒤 숫자 확인(어긋나면 시험 실패)\n\n");
        for (String c : checks) md.append("- ").append(c).append('\n');
        Path out = Path.of("build/load-report.md");
        Files.writeString(out, md.toString());
        System.out.println(md);
        assertThat(r1.errors() + r2.errors() + r3.errors()).isZero();
    }

    static void row(StringBuilder md, Result r) {
        md.append(String.format(Locale.ROOT, "| %s | %d | %d | %.1f초 | %.0f | %dms | %dms | %dms | %dms | %d | %s |%n", r.name(), r.n(), r.conc(),
                r.wallMs() / 1000.0, r.rps(), r.p(0.5), r.p(0.95), r.p(0.99), r.p(1.0), r.errors(), r.codes()));
    }
}
