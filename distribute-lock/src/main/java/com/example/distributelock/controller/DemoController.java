package com.example.distributelock.controller;

import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

/**
 * @author eddie.lee
 * @ProjectName distributed-lock
 * @Package com.example.distributelock.controller
 * @ClassName DemoController
 * @description
 * @date created in 2020-12-15 11:19
 * @modified by
 */
@Slf4j
@RestController
public class DemoController {

    private Lock lock = new ReentrantLock();

    @RequestMapping("singleLock")
    public String singleLock() {
        log.info("Entry method");
        lock.lock();
        try {
            log.info("Access lock");
            Thread.sleep(60000);
            System.out.println("线程名：" + Thread.currentThread().getName());
        } catch (InterruptedException e) {
            e.printStackTrace();
        } finally {
            lock.unlock();
        }
        return "success";
    }

}
