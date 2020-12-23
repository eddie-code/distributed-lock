package com.example.redissonlock;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.ImportResource;

/**
 * @author eddie.lee
 * @ProjectName distributed-lock
 * @Package PACKAGE_NAME
 * @ClassName com.example.distributedemo.Application
 * @description
 * @date created in 2020-12-10 15:22
 * @modified by
 */
@SpringBootApplication
//@ImportResource("classpath*:redisson.xml")
public class Application {

    public static void main(String[] args) {
        SpringApplication.run(Application.class, args);
    }

}
