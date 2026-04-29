package com.mbw;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.modulith.Modulithic;

@SpringBootApplication
@ConfigurationPropertiesScan(basePackages = "com.mbw")
@Modulithic(systemName = "mbw")
public class MbwApplication {

    public static void main(String[] args) {
        SpringApplication.run(MbwApplication.class, args);
    }
}
