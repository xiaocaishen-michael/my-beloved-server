package com.mbw;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.modulith.Modulithic;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@ConfigurationPropertiesScan(basePackages = "com.mbw")
@Modulithic(systemName = "mbw")
@EnableScheduling
public class MbwApplication {

    public static void main(String[] args) {
        SpringApplication.run(MbwApplication.class, args);
    }
}
