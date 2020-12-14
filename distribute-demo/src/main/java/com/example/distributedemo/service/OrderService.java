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

    /* 手动事务 */
    @Autowired
    private PlatformTransactionManager platformTransactionManager;

    /* 手动事务 */
    @Autowired
    private TransactionDefinition transactionDefinition;

//    @Transactional(rollbackFor = Exception.class)
    public synchronized Integer createOrder() throws Exception {

        Product product = null;

        // 对象锁
        synchronized (this) {
            /* 开启 - 手动事务 */
            TransactionStatus transactionStatusSynchronized = platformTransactionManager.getTransaction(transactionDefinition);

            product = productMapper.selectByPrimaryKey(purchaseProductId);

            if (product == null) {
                /* 手动事务回滚 */
                platformTransactionManager.rollback(transactionStatusSynchronized);
                throw new Exception("购买商品：" + purchaseProductId + "不存在");
            }

            /* =================计算库存开始================= */

            // 商品当前库存
            Integer currentCount = product.getCount();
            System.out.println(Thread.currentThread().getName() + "库存数：" + currentCount);

            // 校验库存 （购买数量 大于 商品数量）
            if (purchaseProductNum > currentCount) {
                /* 手动事务回滚 */
                platformTransactionManager.rollback(transactionStatusSynchronized);
                throw new Exception("商品[" + purchaseProductId + "]仅剩余[" + currentCount + "]件, 无法购买");
            }

            productMapper.updateProductCount(purchaseProductNum,
                    "xxx",
                    new Date(),
                    product.getId()
            );
            platformTransactionManager.commit(transactionStatusSynchronized);
        }
        // 检索商品的库存

        // 如果商品库存为负数, 抛出异常

        /* =================计算库存结束================= */

        /* 开启 - 手动事务 */
        TransactionStatus transactionStatus = platformTransactionManager.getTransaction(transactionDefinition);

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

        /* 手动事务提交 */
        platformTransactionManager.commit(transactionStatus);
        return order.getId();
    }

}
