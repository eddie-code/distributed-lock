package com.example.redissonlock;

import lombok.extern.slf4j.Slf4j;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.redisson.Redisson;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.junit4.SpringRunner;

import java.util.concurrent.TimeUnit;


/**
 * @author eddie.lee
 * @ProjectName distributed-lock
 * @Package com.example.redissonlock
 * @ClassName ApplicationTests
 * @description
 * @date created in 2020-12-21 23:27
 * @modified by
 */
@RunWith(SpringRunner.class)
@SpringBootTest
@Slf4j
public class ApplicationTests {

    /**
     * java api
     */
    @Test
    public void testRedissonLock() {
        // 1. Create config object
        Config config = new Config();
        // 2. 如果集群、哨兵模式 useClusterServers
        config.useSingleServer().setAddress("redis://192.168.8.100:6379");
        // Sync and Async API
        RedissonClient redisson = Redisson.create(config);
        // 字符串用于区分业务
        RLock rLock = redisson.getLock("order");
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
    }

}
