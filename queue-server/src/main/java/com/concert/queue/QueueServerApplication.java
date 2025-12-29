package com.concert.queue;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

@SpringBootApplication(scanBasePackages = "com.concert")
@EntityScan("com.concert.common.entity")
@EnableJpaRepositories(basePackages = "com.concert.queue.repository")
@EnableJpaAuditing
public class QueueServerApplication {
    public static void main(String[] args) {
        SpringApplication.run(QueueServerApplication.class, args);
    }
}
