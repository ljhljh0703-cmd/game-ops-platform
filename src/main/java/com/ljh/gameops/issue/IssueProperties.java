// 중복 묶기 기준값과 재발로 볼 기간
package com.ljh.gameops.issue;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * @param duplicateThreshold 이 값 이상 비슷하면 같은 이슈로 묶는다(재발 판단에도 같은 값을 쓴다)
 * @param recurrenceWindowDays 해결한 지 이 기간 안에 비슷한 제보가 오면 재발 의심으로 표시한다
 */
@ConfigurationProperties(prefix = "gameops.issue")
public record IssueProperties(@DefaultValue("0.55") double duplicateThreshold,
                              @DefaultValue("30") int recurrenceWindowDays) {}
