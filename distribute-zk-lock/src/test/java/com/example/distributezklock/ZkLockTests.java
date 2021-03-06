package com.example.distributezklock;

import com.example.distributezklock.lock.ZkLock;
import lombok.extern.slf4j.Slf4j;
import org.apache.curator.RetryPolicy;
import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.CuratorFrameworkFactory;
import org.apache.curator.framework.recipes.locks.InterProcessMutex;
import org.apache.curator.retry.ExponentialBackoffRetry;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.junit4.SpringRunner;

import java.util.concurrent.TimeUnit;

/**
 * @author eddie.lee
 * @ProjectName distributed-lock
 * @Package com.example.distributezklock
 * @ClassName ZkLockTests
 * @description
 * @date created in 2020-12-17 15:19
 * @modified by
 */
@Slf4j
@RunWith(SpringRunner.class)
@SpringBootTest
public class ZkLockTests {

    /**
     * 11:04:23  INFO 28344 --- [168.8.240:2181)] org.apache.zookeeper.ClientCnxn          : Session establishment complete on server 192.168.8.240/192.168.8.240:2181, session id = 0x101be5c5e960005, negotiated timeout = 40000
     * 11:04:23  INFO 28344 --- [           main] c.example.distributezklock.ZkLockTests   : 获得锁的结果：[true]
     * 11:04:23  INFO 28344 --- [           main] org.apache.zookeeper.ZooKeeper           : Session: 0x101be5c5e960005 closed
     * 11:04:23  INFO 28344 --- [ain-EventThread] org.apache.zookeeper.ClientCnxn          : EventThread shut down for session: 0x101be5c5e960005
     * 11:04:23  INFO 28344 --- [           main] c.example.distributezklock.lock.ZkLock   : 释放锁了!
     * 11:04:23  INFO 28344 --- [extShutdownHook] o.s.s.concurrent.ThreadPoolTaskExecutor  : Shutting down ExecutorService 'applicationTaskExecutor'
     * Disconnected from the target VM, address: '127.0.0.1:2814', transport: 'socket'
     */
    @Test
    public void testZkLock() throws Exception {
        ZkLock zkLock = new ZkLock();
        boolean b = zkLock.getLock("order");
        log.info("获得锁的结果：[{}]", b);
        zkLock.close();
    }

    @Test
    public void tesCurator() {
        RetryPolicy retryPolicy = new ExponentialBackoffRetry(1000, 3);
        CuratorFramework client = CuratorFrameworkFactory.newClient("192.168.8.240:2181", retryPolicy);
        client.start();
        InterProcessMutex lock = new InterProcessMutex(client, "/order");
        try {
            // 超时时间
            if (lock.acquire(30, TimeUnit.SECONDS)) {
                try {
                    // do some work inside of the critical section here
                    log.info("抢到锁了!!");
                } finally {
                    lock.release();
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        client.close();
    }

}
