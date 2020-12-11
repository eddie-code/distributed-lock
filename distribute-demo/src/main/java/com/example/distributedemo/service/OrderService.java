package com.example.distributedemo.service;

import com.example.distributedemo.dao.OrderItemMapper;
import com.example.distributedemo.dao.OrderMapper;
import com.example.distributedemo.dao.ProductMapper;
import com.example.distributedemo.model.Order;
import com.example.distributedemo.model.OrderItem;
import com.example.distributedemo.model.Product;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.math.BigDecimal;
import java.util.Date;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

/**
 * @author eddie.lee
 * @ProjectName distributed-lock
 * @Package com.example.distributedemo.service
 * @ClassName OrderService
 * @description
 * @date created in 2020-12-10 15:26
 * @modified by
 */
@Slf4j
@Service
public class OrderService {

    @Resource
    private OrderMapper orderMapper;

    @Resource
    private OrderItemMapper orderItemMapper;

    @Resource
    private ProductMapper productMapper;

    @Autowired
    private PlatformTransactionManager platformTransactionManager;

    @Autowired
    private TransactionDefinition transactionDefinition;

    //购买商品id
    private int purchaseProductId = 100100;

    //购买商品数量
    private int purchaseProductNum = 1;

    private Lock lock = new ReentrantLock();

    /**
     *
     */
    @Transactional(rollbackFor = Exception.class)
    public Integer createOrder() throws Exception {

        Product product = productMapper.selectByPrimaryKey(purchaseProductId);

        if (product == null) {
            throw new Exception("购买商品：" + purchaseProductId + "不存在");
        }

        // 商品当前库存
        Integer count = product.getCount();
        // 校验库存 （购买数量 小于 商品数量）
        if (purchaseProductNum > count) {
            throw new Exception("商品[" + purchaseProductId + "]仅剩余[" + count + "]件, 无法购买");
        }
//        // 计算剩余库存
//        int leftCount = count - purchaseProductNum;
//        // 更新库存
//        product.setCount(leftCount);
//        product.setUpdateUser("xxx");
//        product.setUpdateTime(new Date());

        productMapper.updateProductCount(purchaseProductNum,
                "xxx",
                new Date(),
                product.getId()
        );

        // 检索商品的库存

        // 如果商品库存为负数, 抛出异常

        Order order = Order.builder()
                .orderAmount(product.getPrice().multiply(new BigDecimal(purchaseProductNum)))
                .orderStatus(1)
                .receiverName("xxx")
                .receiverMobile("138001380000")
                .createTime(new Date())
                .createUser("xxx")
                .updateTime(new Date())
                .updateUser("xxx")
                .build();
        orderMapper.insertSelective(order);

        orderItemMapper.insertSelective(OrderItem.builder()
                .orderId(order.getId())
                .productId(product.getId())
                .productPrice(product.getPrice())
                .purchaseNum(purchaseProductNum)
                .createUser("xxx")
                .createTime(new Date())
                .updateTime(new Date())
                .updateUser("xxx")
                .build()
        );

        return order.getId();
    }

}
