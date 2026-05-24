package com.leadmanager.api;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * {@link ConfigurationPropertiesScan} auto-discovers every
 * {@code @ConfigurationProperties}-annotated record under
 * {@code com.leadmanager.api} so adding a new typed config block (JWT,
 * push providers, rate limits, etc.) doesn't require a matching
 * {@code @EnableConfigurationProperties} declaration somewhere.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class LeadManagerApplication {

    public static void main(String[] args) {
        SpringApplication.run(LeadManagerApplication.class, args);
    }
}
