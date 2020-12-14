package com.example.distributedemo.service;

import com.example.distributedemo.Application;
import com.example.distributedemo.service.OrderService;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.junit4.SpringJUnit4ClassRunner;
import org.springframework.test.context.junit4.SpringRunner;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * @author eddie.lee
 * @ProjectName distributed-lock
 * @Package com.example.distributedemo
 * @ClassName ApplicationTests
 * @description
 * @date created in 2020-12-11 13:53
 * @modified by
 */
@RunWith(SpringRunner.class)
@SpringBootTest
public class OrderServiceTests {

    @Autowired
    private OrderService orderService;

    @Test
    public void testConcurrentOrder() throws InterruptedException {

        CountDownLatch countDownLatch = new CountDownLatch(5);
        // 等待五个线程
        CyclicBarrier cyclicBarrier = new CyclicBarrier(5);

        // 线程池, 有五个线程
        ExecutorService es = Executors.newFixedThreadPool(5);
        for (int i = 0; i < 5; i++) {
            es.execute(() -> {
                try {
                    // cyclicBarrier 作用就是把所有线程同时等待,同时并发.达到多线程目的
                    cyclicBarrier.await();
                    Integer orderId = orderService.createOrder();
                    System.out.println("订单ID：[" + orderId + "]");
                } catch (Exception e) {
                    e.printStackTrace();
                } finally {
                    countDownLatch.countDown();
                }
            });
        }
        // 闭锁：等五个线程同时完成后，在继续后面流程
        countDownLatch.await();
        // 关闭线程
        es.shutdown();
    }
}
