package com.example.distributezklock.lock;

import lombok.extern.slf4j.Slf4j;
import org.apache.zookeeper.*;
import org.apache.zookeeper.data.Stat;

import java.io.IOException;
import java.util.Collections;
import java.util.List;

/**
 * @author eddie.lee
 * @ProjectName distributed-lock
 * @Package com.example.distributezklock.lock
 * @ClassName ZkLock
 * @description
 * @date created in 2020-12-17 11:21
 * @modified by
 */
@Slf4j
public class ZkLock implements AutoCloseable, Watcher {

    private ZooKeeper zookeeper;

    private String zNode;

    public ZkLock() throws IOException {
        super();
        this.zookeeper = new ZooKeeper(
                "192.168.8.240:2181",
                60000,
                this
        );
    }

    /**
     * @param businessCode 区分不同锁
     * @return
     */
    public boolean getLock(String businessCode) {
        try {
            // 创建业务根节点
            Stat stat = zookeeper.exists("/" + businessCode, false);
            if (stat == null) {
                zookeeper.create("/" + businessCode,
                        businessCode.getBytes(),
                        ZooDefs.Ids.OPEN_ACL_UNSAFE,
                        CreateMode.PERSISTENT
                );
            }
            // 创建瞬时有序节点 /order/order_00000001
            zNode = zookeeper.create("/" + businessCode + "/" + businessCode + "_", businessCode.getBytes(),
                    ZooDefs.Ids.OPEN_ACL_UNSAFE,
                    CreateMode.EPHEMERAL_SEQUENTIAL
            );
            // 获取业务节点下所有子节点
            List<String> childrenNodes = zookeeper.getChildren("/" + businessCode, false);
            // 子节点升序队列
            Collections.sort(childrenNodes);
            // 获取序号最小的（第一个）子节点
            String firstNode = childrenNodes.get(0);
            // 如果创建的节点是第一个子节点, 则获得锁
            if (zNode.endsWith(firstNode)) {
                return true;
            }
            // 不是第一个子节点, 则监听前一个节点
            String lastNode = firstNode;
            for (String node : childrenNodes) {
                if (zNode.endsWith(node)) {
                    zookeeper.exists("/" + businessCode + "/" + lastNode, true);
                    break;
                } else {
                    lastNode = node;
                }
            }
            //
            synchronized (this) {
                // 等待线程
                wait();
            }

            return true;
        } catch (KeeperException | InterruptedException e) {
            e.printStackTrace();
        }
        return false;
    }

    @Override
    public void close() throws Exception {
        zookeeper.delete(zNode, -1);
        zookeeper.close();
        log.info("释放锁了! ");
    }

    @Override
    public void process(WatchedEvent watchedEvent) {
        if (watchedEvent.getType() == Event.EventType.NodeDeleted) {
            synchronized (this) {
                // 唤起线程
                notify();
            }
        }
    }
}
