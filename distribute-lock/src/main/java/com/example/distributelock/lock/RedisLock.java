package com.example.distributelock.lock;

import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.RedisStringCommands;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.data.redis.core.types.Expiration;

import java.util.Arrays;
import java.util.List;
import java.util.UUID;

/**
 * @author eddie.lee
 * @ProjectName distributed-lock
 * @Package com.example.distributelock.lock
 * @ClassName RedisLock
 * @description
 * @date created in 2020-12-16 15:40
 * @modified by
 */
@Slf4j
public class RedisLock implements AutoCloseable {

    private RedisTemplate redisTemplate;

    /**
     * redis键
     */
    private String key;

    /**
     * redis值
     */
    private String value;

    /**
     * 单位：秒
     */
    private int expireTime;

    public RedisLock(RedisTemplate redisTemplate, String key, int expireTime) {
        this.key = key;
        this.expireTime = expireTime;
        this.redisTemplate = redisTemplate;
        // 可以传入, 也可以自己生成
        this.value = UUID.randomUUID().toString();
    }

    /**
     * 获取分布式锁
     */
    public boolean getLock() {
        RedisCallback<Boolean> redisCallback = connection -> {
            // 设置NX
            RedisStringCommands.SetOption setOption = RedisStringCommands.SetOption.ifAbsent();
            // 设置过期时间
            Expiration expiration = Expiration.seconds(expireTime);

            // 序列化 key value
            byte[] eddieKey = redisTemplate.getKeySerializer().serialize(key);
            byte[] redisValue = redisTemplate.getKeySerializer().serialize(value);

            // 执行 setnx 操作
            assert eddieKey != null;
            assert redisValue != null;
            return connection.set(eddieKey, redisValue, expiration, setOption);
        };
        // 获取分布式锁
        return (boolean) redisTemplate.execute(redisCallback);
    }

    /**
     * 释放分布式锁
     */
    public boolean unLock() {
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
        return result;

    }

    /**
     * jdk1.7 出的特性
     */
    @Override
    public void close() throws Exception {
        unLock();
    }

}
