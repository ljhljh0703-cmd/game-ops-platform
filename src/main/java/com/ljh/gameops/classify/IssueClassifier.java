// 제보 본문을 이슈 분류·심각도·제목으로 바꾸는 분류기
package com.ljh.gameops.classify;

public interface IssueClassifier {
    Classification classify(String gameCode, String body);
}
