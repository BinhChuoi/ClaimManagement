package com.fightforfuture.cmp.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "aws.athena")
public class AthenaProperties {

    private String workgroup;
    private String database;
    private long pollIntervalMs = 2000;
    private int maxPollAttempts = 60;
}
