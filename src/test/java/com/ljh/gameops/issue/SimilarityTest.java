// 정규화·유사도 — 띄어쓰기·감탄사·링크 차이는 같은 제보로 본다
package com.ljh.gameops.issue;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SimilarityTest {
    @Test
    void 감탄사와_링크와_문장부호는_지운다() {
        assertThat(TextNormalizer.normalize("5웨이브에서 튕겨요ㅠㅠ!! https://x.y/z"))
                .isEqualTo("5웨이브에서 튕겨요");
    }

    @Test
    void 띄어쓰기만_다른_제보는_거의_같다() {
        double s = Similarity.jaccard(TextNormalizer.normalize("5웨이브에서 게임이 튕겨요"),
                TextNormalizer.normalize("5 웨이브에서 게임이  튕겨요ㅋㅋ"));
        assertThat(s).isGreaterThan(0.9);
    }

    @Test
    void 다른_내용은_기준_아래다() {
        double s = Similarity.jaccard(TextNormalizer.normalize("5웨이브에서 게임이 튕겨요"),
                TextNormalizer.normalize("루비 타워 공격력이 너무 약해요"));
        assertThat(s).isLessThan(0.3);
    }

    @Test
    void 같은_정규화_본문은_같은_키() {
        assertThat(TextNormalizer.key("a b")).isEqualTo(TextNormalizer.key("a b")).hasSize(64);
    }
}
