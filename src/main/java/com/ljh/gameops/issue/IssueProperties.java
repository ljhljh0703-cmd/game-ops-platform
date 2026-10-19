// 중복 묶기 기준값
package com.ljh.gameops.issue;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "gameops.issue")
public record IssueProperties(double duplicateThreshold) {}
