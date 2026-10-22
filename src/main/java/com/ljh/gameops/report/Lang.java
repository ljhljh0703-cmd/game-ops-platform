// 제보 언어 알아보기 — 글자 종류만 센다(외부 호출 없음). 한글이 조금이라도 많으면 한국어로 본다.
// ko · ja · zh · en · other. 확실하지 않으면 ko(지금까지와 같게 처리).
package com.ljh.gameops.report;

public final class Lang {
    public static final String KO = "ko", JA = "ja", ZH = "zh", EN = "en", OTHER = "other";

    private Lang() {}

    public static String detect(String text) {
        if (text == null) return KO;
        int hangul = 0, kana = 0, han = 0, latin = 0, otherLetters = 0;
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            i += Character.charCount(cp);
            if (!Character.isLetter(cp)) continue;
            Character.UnicodeScript s = Character.UnicodeScript.of(cp);
            switch (s) {
                case HANGUL -> hangul++;
                case HIRAGANA, KATAKANA -> kana++;
                case HAN -> han++;
                case LATIN -> latin++;
                default -> otherLetters++;
            }
        }
        // 「[게임진단 v1.2 · Chrome …]」 같은 영문 줄이 붙은 한국어 글이 많아, 한글이 몇 자라도 있으면 한국어
        if (hangul >= 2 || hangul > 0 && hangul * 3 >= latin) return KO;
        if (kana > 0) return JA;
        if (han >= 2) return ZH;
        if (latin >= 3 && latin >= otherLetters) return EN;
        if (otherLetters >= 3) return OTHER;
        return KO;
    }

    public static boolean isKorean(String lang) { return lang == null || KO.equals(lang); }

    /** 슬랙 카드에 쓰는 이름 */
    public static String label(String lang) {
        if (lang == null) return "한국어";
        return switch (lang) {
            case KO -> "한국어";
            case JA -> "일본어";
            case ZH -> "중국어";
            case EN -> "영어";
            default -> "외국어";
        };
    }
}
