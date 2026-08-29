package com.techjobs.finder;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@ConfigurationPropertiesScan
@EnableScheduling
public class TechJobsFinderApplication {

    public static void main(String[] args) {
        SpringApplication.run(TechJobsFinderApplication.class, args);
    }
}
