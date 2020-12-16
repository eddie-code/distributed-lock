package com.example.distributelock.service;

import com.example.distributelock.lock.RedisLock;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * @author eddie.lee
 * @ProjectName distributed-lock
 * @Package com.example.distributelock.service
 * @ClassName SchedulerService
 * @description
 * @date created in 2020-12-16 16:14
 * @modified by
 */
@Slf4j
@Service
public class SchedulerService {

    @Autowired
    private RedisTemplate redisTemplate;

    /**
     * 使用redis.setnx实现分布式锁：
     *      每5秒发送短信给用户
     */
    @Scheduled(cron = "0/5 * * * * ?")
    public void sendSms() {
        try (RedisLock redisLock = new RedisLock(redisTemplate, "smsKey", 30)) {
            if (redisLock.getLock()) {
                log.info("向 13800138000 发送一条趣味短信! ");
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

}
