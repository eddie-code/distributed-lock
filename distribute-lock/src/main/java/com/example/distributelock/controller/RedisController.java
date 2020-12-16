package com.example.distributelock.controller;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.connection.RedisStringCommands;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.data.redis.core.types.Expiration;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Arrays;
import java.util.List;
import java.util.UUID;

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
        String key = "eddieKey";
        String value = UUID.randomUUID().toString();

        RedisCallback<Boolean> redisCallback = connection -> {
            // 设置NX
            RedisStringCommands.SetOption setOption = RedisStringCommands.SetOption.ifAbsent();
            // 设置过期时间
            Expiration expiration = Expiration.seconds(30);

            // 序列化 key value
            byte[] eddieKey = redisTemplate.getKeySerializer().serialize(key);
            byte[] redisValue = redisTemplate.getKeySerializer().serialize(value);

            // 执行 setnx 操作
            assert eddieKey != null;
            assert redisValue != null;
            return connection.set(eddieKey, redisValue, expiration, setOption);
        };

        // 获取分布式锁
        Boolean b = (Boolean) redisTemplate.execute(redisCallback);
        if (b) {
            log.info("抢到锁了!");
            try {
                // 15s
                Thread.sleep(15000);
            } catch (InterruptedException e) {
                e.printStackTrace();
            } finally {
                // lua脚本
                String luaScript = "if redis.call(\"get\",KEYS[1]) == ARGV[1] then\n" +
                        "    return redis.call(\"del\",KEYS[1])\n" +
                        "else\n" +
                        "    return 0\n" +
                        "end";
                RedisScript<Boolean> redisScript = RedisScript.of(luaScript, Boolean.class);

                List<String> keys = Arrays.asList(key);

                Boolean result = (Boolean) redisTemplate.execute(redisScript, keys, value);

                log.info("释放锁结果：[{}]", result);

            }
        }

        log.info("success");
        return "success";
    }

}
