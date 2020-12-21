package com.example.redissonlock.controller;

import lombok.extern.slf4j.Slf4j;
import org.redisson.Redisson;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.concurrent.TimeUnit;

/**
 * @author eddie.lee
 * @ProjectName distributed-lock
 * @Package com.example.redissonlock.controller
 * @ClassName RedissonLockController
 * @description
 * @date created in 2020-12-21 23:40
 * @modified by
 */
@Slf4j
@RestController
public class RedissonLockController {

    @RequestMapping("redissonLock")
    public String redissonLock() {
        // 1. Create config object
        Config config = new Config();
        // 2. 如果集群、哨兵模式 useClusterServers
        config.useSingleServer().setAddress("redis://192.168.8.100:6379");
        // Sync and Async API
        RedissonClient redisson = Redisson.create(config);
        // 字符串用于区分业务
        RLock rLock = redisson.getLock("order");
        log.info("进入方法！");
        // 设置锁过期时间, 时间超过30秒, 就会自动释放锁
        rLock.lock(30, TimeUnit.SECONDS);
        log.info("抢到锁了!");
        try {
            Thread.sleep(10000);
        } catch (InterruptedException e) {
            e.printStackTrace();
        } finally {
            rLock.unlock();
            log.info("释放了RedissonLock锁！");
        }
        log.info("redissonLock() 执行完成！");

        return "redissonLock() 执行完成！";
    }

}
