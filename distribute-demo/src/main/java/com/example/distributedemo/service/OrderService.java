package com.example.distributedemo.service;

import com.example.distributedemo.dao.OrderItemMapper;
import com.example.distributedemo.dao.OrderMapper;
import com.example.distributedemo.dao.ProductMapper;
import com.example.distributedemo.model.Order;
import com.example.distributedemo.model.OrderItem;
import com.example.distributedemo.model.Product;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.math.BigDecimal;
import java.util.Date;

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

    //购买商品id
    private int purchaseProductId = 100100;

    //购买商品数量
    private int purchaseProductNum = 1;

    /**
     *
     */
    @Transactional(rollbackFor = Exception.class)
    public Integer createOrder() throws Exception {

        Product product = productMapper.selectByPrimaryKey(purchaseProductId);

        if (product == null) {
            throw new Exception("购买商品：" + purchaseProductId + "不存在");
        }

        /* =================计算库存开始================= */

        // 商品当前库存
        Integer currentCount = product.getCount();
        System.out.println(Thread.currentThread().getName() + "库存数：" + currentCount);

        // 校验库存 （购买数量 大于 商品数量）
        if (purchaseProductNum > currentCount) {
            throw new Exception("商品[" + purchaseProductId + "]仅剩余[" + currentCount + "]件, 无法购买");
        }
        // 计算剩余库存
//        int leftCount = currentCount - purchaseProductNum;
        // 更新库存
//        product.setCount(leftCount);
//        product.setUpdateUser("xxx");
//        product.setUpdateTime(new Date());
//        Thread.sleep(3000);
        // # timeout exceeded; try restarting transaction; nested exception is com.mysql.cj.jdbc.exceptions.MySQLTransactionRollbackException: Lock wait timeout exceeded; try restarting transaction
//        productMapper.updateByPrimaryKeySelective(product);

        // 不推荐代码扣减库存, 这里使用数据库去扣减, 数据库有行锁, 避免并发问题
        productMapper.updateProductCount(purchaseProductNum,
                "xxx",
                new Date(),
                product.getId()
        );

        /* =================计算库存结束================= */

        Order order = Order.builder()
                .orderAmount(product.getPrice().multiply(new BigDecimal(purchaseProductNum)))
                .orderStatus(1)
                .receiverName("xxx")
                .receiverMobile("13800138000")
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
                .updateUser("xxx")
                .updateTime(new Date())
                .build()
        );

        return order.getId();
    }

}
