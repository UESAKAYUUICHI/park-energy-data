package com.parkenergydata;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@ConfigurationPropertiesScan
@MapperScan("com.parkenergydata.repository")
@EnableScheduling
public class ParkEnergyDataApplication {

    public static void main(String[] args) {
        SpringApplication.run(ParkEnergyDataApplication.class, args);
    }

}
