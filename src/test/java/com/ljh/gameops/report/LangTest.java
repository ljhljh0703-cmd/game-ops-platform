// 제보 언어 알아보기 — 글자 종류로만. 영문 진단 줄이 붙은 한국어 글은 한국어
package com.ljh.gameops.report;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class LangTest {
    @Test
    void 언어를_글자_종류로_고른다() {
        assertThat(Lang.detect("5웨이브에서 게임이 튕겨요")).isEqualTo("ko");
        assertThat(Lang.detect("The game crashes every time I reach wave 5")).isEqualTo("en");
        assertThat(Lang.detect("5ウェーブで毎回アプリが落ちます")).isEqualTo("ja");
        assertThat(Lang.detect("游戏在第五波总是闪退")).isEqualTo("zh");
        assertThat(Lang.detect("Игра вылетает на пятой волне")).isEqualTo("other");
    }

    @Test
    void 영문_진단_줄이_붙어도_한국어() {
        assertThat(Lang.detect("보스에서 튕김 [게임진단 v1.2.3 · Chrome 130 · iPhone · 오류: TypeError: x is undefined]")).isEqualTo("ko");
    }

    @Test
    void 글자가_거의_없으면_한국어로_본다() {
        assertThat(Lang.detect("ㅠㅠ 123 !!")).isEqualTo("ko");
        assertThat(Lang.detect("ok")).isEqualTo("ko");
        assertThat(Lang.detect(null)).isEqualTo("ko");
    }
}
