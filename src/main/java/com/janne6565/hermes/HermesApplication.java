package com.janne6565.hermes;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class HermesApplication {

    public static void main(String[] args) {
        SpringApplication.run(HermesApplication.class, args);
    }
}
