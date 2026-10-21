// 재현 정보 — QA·개발자가 버그 제보를 받으면 먼저 묻는 것(게임 버전·기기·재현 순서·스크린샷).
// /report 입력칸으로 받은 값이 먼저이고, 없으면 글에서 버전·기기 낱말을 찾아 채운다. 못 찾으면 비워 둔다(지어내지 않음).
package com.ljh.gameops.report;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public record ReproInfo(String version, String device, String steps) {
    static final int VERSION_MAX = 60, DEVICE_MAX = 100, STEPS_MAX = 1000;

    public static final ReproInfo NONE = new ReproInfo(null, null, null);

    /** 1.2 · v1.2.3 · 버전 1.4 · ver 2.0.1b */
    private static final Pattern VERSION = Pattern.compile(
            "(?i)(?:(?<![\\w.])v|버전\\s*:?\\s*|ver\\.?\\s*|빌드\\s*:?\\s*)(\\d+(?:\\.\\d+){1,3}[a-z0-9-]*)");
    /** 기기·브라우저 낱말. 모델명이 붙어 있으면 함께(아이폰 15, Galaxy S23) */
    private static final Pattern DEVICE = Pattern.compile(
            "(?i)(?<![a-z])(아이폰|iphone|갤럭시|galaxy|아이패드|ipad|안드로이드|android|윈도우|windows|맥북|macbook|mac|pc|"
                    + "크롬|chrome|사파리|safari|엣지|edge|파이어폭스|firefox|카카오톡|스팀덱|steam ?deck)"
                    + "(\\s?(?:[a-z]{0,3}\\d{1,3}(?:\\s?(?:pro|max|plus|ultra|울트라|프로|미니|mini|\\+))?|탭\\s?s?\\d+))?(?![a-z])");

    public ReproInfo {
        version = clean(version, VERSION_MAX);
        device = clean(device, DEVICE_MAX);
        steps = clean(steps, STEPS_MAX);
    }

    public static ReproInfo of(String version, String device, String steps) {
        return new ReproInfo(version, device, steps);
    }

    /**
     * 게임 안 「제보용 정보 복사」 버튼이 만드는 한 줄(결정 37):
     * 「[게임진단 v1.2.3 · Chrome 130 · iPhone · 오류: TypeError: x is undefined]」. 플레이어가 글에 붙여 넣으면 버전·기기·오류를 그대로 받는다
     */
    public record Diag(String version, String device, String error) {}

    private static final Pattern DIAG = Pattern.compile("\\[게임진단 ([^\\]\\n]{1,600})\\]");

    public static Diag diag(String text) {
        if (text == null) return null;
        Matcher m = DIAG.matcher(text);
        if (!m.find()) return null;
        String version = null, error = null;
        List<String> device = new ArrayList<>();
        for (String part : m.group(1).split(" · ")) {
            String p = part.strip();
            if (error != null) { error = error + " · " + p; continue; } // 오류 글 안의 「 · 」
            if (p.startsWith("오류:")) error = p.substring(3).strip();
            else if (version == null && p.matches("v\\d+(\\.\\d+){0,3}[a-z0-9-]*")) version = p.substring(1);
            else if (!p.isEmpty()) device.add(p);
        }
        return new Diag(version, device.isEmpty() ? null : String.join(", ", device), error == null || error.isBlank() ? null : error);
    }

    /** 글에서 버전·기기만 찾는다. 재현 순서는 글 자체라 따로 뽑지 않는다. 「게임진단」 줄이 있으면 그 값이 먼저 */
    public static ReproInfo parse(String text) {
        if (text == null || text.isBlank()) return NONE;
        Diag dg = diag(text);
        if (dg != null && (dg.version() != null || dg.device() != null)) {
            ReproInfo rest = parse(DIAG.matcher(text).replaceAll(" "));
            return new ReproInfo(dg.version() != null ? dg.version() : rest.version, dg.device() != null ? dg.device() : rest.device, null);
        }
        Matcher v = VERSION.matcher(text);
        String version = v.find() ? v.group(1) : null;
        LinkedHashSet<String> devices = new LinkedHashSet<>();
        Matcher d = DEVICE.matcher(text);
        while (d.find() && devices.size() < 3) devices.add(d.group().strip());
        return new ReproInfo(version, devices.isEmpty() ? null : String.join(", ", devices), null);
    }

    /** 입력칸 값이 먼저, 빈 칸만 글에서 찾은 값으로 */
    public static ReproInfo merge(ReproInfo explicit, ReproInfo parsed) {
        ReproInfo a = explicit == null ? NONE : explicit, b = parsed == null ? NONE : parsed;
        return new ReproInfo(a.version != null ? a.version : b.version, a.device != null ? a.device : b.device,
                a.steps != null ? a.steps : b.steps);
    }

    public boolean isEmpty() { return version == null && device == null && steps == null; }

    /** 플레이어에게 더 물어볼 것(스크린샷은 첨부 여부로 따로 본다) */
    public List<String> missing(boolean hasScreenshot) {
        List<String> out = new ArrayList<>();
        if (version == null) out.add("게임 버전");
        if (device == null) out.add("기기");
        if (steps == null) out.add("재현 순서");
        if (!hasScreenshot) out.add("스크린샷");
        return out;
    }

    private static String clean(String s, int max) {
        if (s == null) return null;
        String t = s.strip();
        if (t.isEmpty()) return null;
        return t.length() > max ? t.substring(0, max) : t;
    }

    /**
     * 한 이슈에 묶인 제보들의 재현 정보를 모은 것 — 버전·기기는 서로 다른 값 모두(최대 5), 재현 순서는 처음 적힌 것,
     * 스크린샷은 주소 모두(최대 6). 버전이 여러 개면 「여러 버전에서 난다」는 것 자체가 QA 단서다.
     */
    public record Summary(List<String> versions, List<String> devices, String steps, List<String> screenshots, int reportsWithInfo) {
        public static final Summary EMPTY = new Summary(List.of(), List.of(), null, List.of(), 0);

        /** 제보 한 건의 재현 정보 조각(먼저 받은 제보부터 넘긴다) */
        public record Part(String version, String device, String steps, String attachments) {}

        public static Summary of(List<Report> reports) {
            List<Report> sorted = new ArrayList<>(reports);
            sorted.sort(Comparator.comparing(Report::getId, Comparator.nullsLast(Comparator.naturalOrder())));
            return from(sorted.stream().map(r -> new Part(r.getBuildVersion(), r.getDevice(), r.getReproSteps(), r.getAttachmentUrls())).toList());
        }

        public static Summary from(List<Part> parts) {
            LinkedHashSet<String> vs = new LinkedHashSet<>(), ds = new LinkedHashSet<>(), shots = new LinkedHashSet<>();
            String steps = null;
            int with = 0;
            for (Part r : parts) {
                boolean any = false;
                if (r.version() != null && vs.size() < 5) { vs.add(r.version()); any = true; }
                if (r.device() != null && ds.size() < 5) { ds.add(r.device()); any = true; }
                if (r.steps() != null) { if (steps == null) steps = r.steps(); any = true; }
                for (String u : urls(r.attachments())) { if (shots.size() < 6) shots.add(u); any = true; }
                if (any) with++;
            }
            return new Summary(List.copyOf(vs), List.copyOf(ds), steps, List.copyOf(shots), with);
        }

        public boolean isEmpty() { return versions.isEmpty() && devices.isEmpty() && steps == null && screenshots.isEmpty(); }

        /** 카드 한 줄: 「버전 1.2, 1.3 · 기기 아이폰 15 · 재현 순서 있음 · 스크린샷 2장」. 비면 「재현 정보 없음」 */
        public String line() {
            if (isEmpty()) return "재현 정보 없음";
            List<String> parts = new ArrayList<>();
            if (!versions.isEmpty()) parts.add("버전 " + String.join(", ", versions));
            if (!devices.isEmpty()) parts.add("기기 " + String.join(", ", devices));
            parts.add(steps != null ? "재현 순서 있음" : "재현 순서 없음");
            if (!screenshots.isEmpty()) parts.add("스크린샷 " + screenshots.size() + "장");
            return String.join(" · ", parts);
        }
    }

    static List<String> urls(String joined) {
        if (joined == null || joined.isBlank()) return List.of();
        // 디스코드가 올려 둔 첨부만 스크린샷으로 본다(아무 주소나 지시서·티켓에 들어가지 않게)
        return Arrays.stream(joined.split("\\s+"))
                .filter(u -> u.startsWith("https://cdn.discordapp.com/") || u.startsWith("https://media.discordapp.net/")).toList();
    }
}
