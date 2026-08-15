package com.example.minecraftcontrol;

import com.example.minecraftcontrol.config.MinecraftProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
@EnableConfigurationProperties(MinecraftProperties.class)
public class MinecraftControlApplication {
    public static void main(String[] args) {
        SpringApplication.run(MinecraftControlApplication.class, args);
    }
}
