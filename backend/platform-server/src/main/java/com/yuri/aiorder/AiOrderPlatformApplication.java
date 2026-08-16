package com.yuri.aiorder;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class AiOrderPlatformApplication {

    public static void main(String[] args) {
        SpringApplication.run(AiOrderPlatformApplication.class, args);
    }
}
