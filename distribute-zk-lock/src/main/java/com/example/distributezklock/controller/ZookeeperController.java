package com.example.distributezklock.controller;

import com.example.distributezklock.lock.ZkLock;
import lombok.extern.slf4j.Slf4j;
import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.recipes.locks.InterProcessMutex;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.concurrent.TimeUnit;

/**
 * @author eddie.lee
 * @ProjectName distributed-lock
 * @Package com.example.distributezklock.controller
 * @ClassName ZookeeperController
 * @description
 * @date created in 2020-12-18 11:01
 * @modified by
 */
@Slf4j
@RestController
public class ZookeeperController {

    @Autowired
    private CuratorFramework curatorFramework;

    @RequestMapping("zkLock")
    public String zookeeperLock() {
        log.info("进入方法");
        try(ZkLock zkLock = new ZkLock()) {
            if (zkLock.getLock("order")) {
                log.info("抢到锁了! ");
                Thread.sleep(10000);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        log.info("方法已完成");
        return "方法已完成";
    }

    @RequestMapping("curatorLock")
    public String curatorLock() {
        log.info("进入方法");
        InterProcessMutex lock = new InterProcessMutex(curatorFramework, "/order");
        try {
            if (lock.acquire(30, TimeUnit.SECONDS)) {
                log.info("抢到锁了!!");
                Thread.sleep(10000);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }finally {
            try {
                lock.release();
                log.info("释放了Curator锁！");
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
        log.info("方法已完成");
        return "方法已完成";
    }

}
