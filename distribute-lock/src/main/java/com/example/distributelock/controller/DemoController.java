package com.example.distributelock.controller;

import com.example.distributelock.dao.DistributeLockMapper;
import com.example.distributelock.model.DistributeLock;
import lombok.extern.slf4j.Slf4j;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.annotation.Resource;
import java.time.LocalDate;
import java.time.LocalDateTime;
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

    @Resource
    private DistributeLockMapper distributeLockMapper;

//    private Lock lock = new ReentrantLock();
//
//    @RequestMapping("singleLock")
//    public String singleLock() {
//        log.info("Entry method");
//        lock.lock();
//        try {
//            log.info("Access lock");
//            Thread.sleep(60000);
//            System.out.println("线程名：" + Thread.currentThread().getName());
//        } catch (InterruptedException e) {
//            e.printStackTrace();
//        } finally {
//            lock.unlock();
//        }
//        return "success";
//    }

    @RequestMapping("singleLock")
    @Transactional(rollbackFor = Exception.class)
    public String singleLock() throws Exception {
        log.info("Entry method");
        // 检索demo的锁
        DistributeLock distributeLock = distributeLockMapper.selectDistributeLock("demo");
        if (distributeLock == null) {
            throw new Exception("分布式锁找不到");
        }
        log.info("Access lock");
        try {
            Thread.sleep(20000);
            System.out.println("时间：" + LocalDateTime.now() + " 线程名：" + Thread.currentThread().getName());
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
        return "success";
    }


}
