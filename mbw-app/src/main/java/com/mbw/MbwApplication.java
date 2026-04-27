package com.mbw;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.modulith.Modulithic;

@SpringBootApplication
@Modulithic(systemName = "mbw")
public class MbwApplication {

    public static void main(String[] args) {
        SpringApplication.run(MbwApplication.class, args);
    }
}
