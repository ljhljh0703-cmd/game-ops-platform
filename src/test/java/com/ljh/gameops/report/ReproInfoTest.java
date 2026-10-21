// 재현 정보 — 글에서 버전·기기를 찾고, 입력칸 값이 먼저, 빠진 것만 묻고, 묶인 제보들을 한 줄로 모은다
package com.ljh.gameops.report;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ReproInfoTest {
    @Test
    void 글에서_버전과_기기를_찾는다() {
        var r = ReproInfo.parse("v1.2.3 아이폰 15 프로에서 보스 웨이브 들어가면 튕겨요. 크롬에서도 그래요");
        assertThat(r.version()).isEqualTo("1.2.3");
        assertThat(r.device()).isEqualTo("아이폰 15 프로, 크롬");
        assertThat(r.steps()).isNull(); // 재현 순서는 지어내지 않는다

        assertThat(ReproInfo.parse("버전 2.0 갤럭시 S23 울트라").device()).isEqualTo("갤럭시 S23 울트라");
        assertThat(ReproInfo.parse("빌드: 0.9.1b 에서 결제가 안 돼요").version()).isEqualTo("0.9.1b");
    }

    @Test
    void 숫자만_있거나_낱말_일부면_찾지_않는다() {
        var r = ReproInfo.parse("보석 3.5개가 사라짐, 매크로 쓰는 사람 있음, pcs 단위");
        assertThat(r.version()).isNull();
        assertThat(r.device()).isNull();
        assertThat(ReproInfo.parse(null)).isEqualTo(ReproInfo.NONE);
    }

    @Test
    void 입력칸_값이_먼저고_빈_칸만_글에서() {
        var m = ReproInfo.merge(ReproInfo.of("1.4", null, "3웨이브 일시정지 후 재개"), ReproInfo.parse("v1.2 안드로이드"));
        assertThat(m).isEqualTo(new ReproInfo("1.4", "안드로이드", "3웨이브 일시정지 후 재개"));
    }

    @Test
    void 빠진_것만_묻는다() {
        assertThat(ReproInfo.parse("튕겨요").missing(false)).containsExactly("게임 버전", "기기", "재현 순서", "스크린샷");
        assertThat(ReproInfo.of("1.2", "아이폰", "보스전 시작").missing(true)).isEmpty();
    }

    @Test
    void 묶인_제보들을_한_줄로_모은다() {
        var s = ReproInfo.Summary.from(List.of(
                new ReproInfo.Summary.Part("1.2", "아이폰 15", null, null),
                new ReproInfo.Summary.Part("1.3", "아이폰 15", "보스전 시작하면", "https://cdn.discordapp.com/a.png\nhttps://cdn.discordapp.com/b.png"),
                new ReproInfo.Summary.Part(null, null, "다른 순서", "javascript:alert(1)")));
        assertThat(s.versions()).containsExactly("1.2", "1.3");
        assertThat(s.devices()).containsExactly("아이폰 15");
        assertThat(s.steps()).isEqualTo("보스전 시작하면"); // 처음 적힌 것
        assertThat(s.screenshots()).hasSize(2); // https 만
        assertThat(s.line()).isEqualTo("버전 1.2, 1.3 · 기기 아이폰 15 · 재현 순서 있음 · 스크린샷 2장");
        assertThat(ReproInfo.Summary.EMPTY.line()).isEqualTo("재현 정보 없음");
    }
}
