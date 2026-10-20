// 중복 묶기 기준값과 재발로 볼 기간
package com.ljh.gameops.issue;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * @param duplicateThreshold 이 값 이상 비슷하면 같은 이슈로 묶는다(재발 판단에도 같은 값을 쓴다)
 * @param recurrenceWindowDays 해결한 지 이 기간 안에 비슷한 제보가 오면 재발 의심으로 표시한다
 * @param sameCategoryOnly 분류가 같을 때만 묶는다(재발도). 글자는 많이 겹치는데 증상이 다른 글(「튕겨요/느려요」)을 걸러
 *                         기준값을 낮출 수 있게 한다(운영 설계서 P9, docs/EVAL.md §3). 끄면 예전처럼 글자만 본다
 */
@ConfigurationProperties(prefix = "gameops.issue")
public record IssueProperties(@DefaultValue("0.20") double duplicateThreshold,
                              @DefaultValue("30") int recurrenceWindowDays,
                              @DefaultValue("true") boolean sameCategoryOnly) {}
