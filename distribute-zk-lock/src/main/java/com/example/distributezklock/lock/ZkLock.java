package com.example.distributezklock.lock;

import org.apache.zookeeper.*;
import org.apache.zookeeper.data.Stat;

import java.io.IOException;

/**
 * @author eddie.lee
 * @ProjectName distributed-lock
 * @Package com.example.distributezklock.lock
 * @ClassName ZkLock
 * @description
 * @date created in 2020-12-17 11:21
 * @modified by
 */
public class ZkLock implements AutoCloseable, Watcher {

    private ZooKeeper zookeeper;

    public ZkLock() throws IOException {
        super();
        this.zookeeper = new ZooKeeper(
                "192.168.8.240:2181",
                1000,
                this
        );
    }

    /**
     * @param businessCode 区分不同锁
     * @return
     */
    public boolean getLock(String businessCode) {
        try {
            Stat stat = zookeeper.exists("/" + businessCode, false);
            if (stat != null) {
                zookeeper.create("/" + businessCode,
                        businessCode.getBytes(),
                        ZooDefs.Ids.OPEN_ACL_UNSAFE,
                        CreateMode.PERSISTENT
                );
            }
        } catch (KeeperException | InterruptedException e) {
            e.printStackTrace();
        }
        return false;
    }

    @Override
    public void close() throws Exception {

    }

    @Override
    public void process(WatchedEvent watchedEvent) {

    }
}
