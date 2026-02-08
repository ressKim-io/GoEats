package com.goeats;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cache.annotation.EnableCaching;

@SpringBootApplication
@EnableCaching
public class GoEatsApplication {

    public static void main(String[] args) {
        SpringApplication.run(GoEatsApplication.class, args);
    }
}
