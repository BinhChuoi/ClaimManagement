package com.fightforfuture.cmp.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.athena.AthenaClient;

@Configuration
public class AthenaConfig {

    @Bean
    public AthenaClient athenaClient(@Value("${aws.region}") String region) {
        return AthenaClient.builder()
                .region(Region.of(region))
                // Picks up credentials from: env vars → ~/.aws/credentials → EC2 instance role
                .credentialsProvider(DefaultCredentialsProvider.create())
                .build();
    }
}
