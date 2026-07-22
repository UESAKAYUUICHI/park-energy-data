package com.parkenergydata;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.mybatis.spring.annotation.MapperScan;

@SpringBootApplication
@ConfigurationPropertiesScan
@MapperScan("com.parkenergydata.repository")
public class ParkEnergyDataApplication {

    public static void main(String[] args) {
        SpringApplication.run(ParkEnergyDataApplication.class, args);
    }

}
