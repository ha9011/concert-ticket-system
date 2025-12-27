package com.concert.queue;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication(scanBasePackages = "com.concert")
public class QueueServerApplication {
    public static void main(String[] args) {
        SpringApplication.run(QueueServerApplication.class, args);
    }
}
