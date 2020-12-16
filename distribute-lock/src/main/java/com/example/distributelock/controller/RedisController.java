package com.example.distributelock.controller;

import com.example.distributelock.lock.RedisLock;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;


/**
 * @author eddie.lee
 * @ProjectName distributed-lock
 * @Package com.example.distributelock.controller
 * @ClassName RedisController
 * @description setNX，是set if not exists 的缩写，
 *              也就是只有不存在的时候才设置, 设置成功时返回 1 ， 设置失败时返回 0 。
 *              可以利用它来实现锁的效果，但是很多人在使用的过程中都有一些问题没有考虑到。
 * @date created in 2020-12-16 11:37
 * @modified by
 */
@Slf4j
@RestController
public class RedisController {

    @Autowired
    private RedisTemplate redisTemplate;

    @RequestMapping("redisLock")
    public String redisLock() {
        log.info("进入方法");

        // 传统写法
//        RedisLock redisLock = new RedisLock(redisTemplate, "eddieKey",30);
//        if (redisLock.getLock()) {
//            log.info("抢到锁了!");
//            try {
//                // 15s
//                Thread.sleep(15000);
//            } catch (InterruptedException e) {
//                e.printStackTrace();
//            } finally {
//                // **** implements AutoCloseable 就不需要finally 来释放锁****
//                boolean result = redisLock.unLock();
//                log.info("释放锁结果：[{}]", result);
//            }
//        }

        // jdk1.7之后添加的写法 try后面加入
        try (RedisLock redisLock = new RedisLock(redisTemplate, "eddieKey", 30)) {
            if (redisLock.getLock()) {
                log.info("抢到锁了!");
                Thread.sleep(15000);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }

        log.info("success");
        return "success";
    }

}
