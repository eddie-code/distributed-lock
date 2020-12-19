package com.example.distributezklock.controller;

import com.example.distributezklock.lock.ZkLock;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

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

}
