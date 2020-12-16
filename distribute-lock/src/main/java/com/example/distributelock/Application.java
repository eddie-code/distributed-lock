package com.example.distributelock;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * @author eddie.lee
 * @ProjectName distributed-lock
 * @Package PACKAGE_NAME
 * @ClassName com.example.distributelock.Application
 * @description
 * @date created in 2020-12-10 15:22
 * @modified by
 */
@EnableScheduling
@SpringBootApplication
@MapperScan("com.example.distributelock.dao")
public class Application {

    public static void main(String[] args) {
        SpringApplication.run(Application.class, args);
    }

}
