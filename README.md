[TOC]

# distributed-lock

## distribute-demo

### 模拟一
- 描述：问题出现的原因：假设有两个事务A和事务B,他们两个都存在update 同一条记录，A 先修改，但是没有提交事务，B也想修改但是一直等，直到等到了超过了innodb_lock_wait_timeout所设置的时间，就会爆出此异常
- 错误：Lock wait timeout exceeded; try restarting transaction
- 解析：第一个图中的update 语句执行完成后并未commit； 接着第二个执行语句执行后会发发现一直在运行，并没有停止，直到出现
- 解决：kill 掉对应的进程就可以了

```sql
mysql> show  processlist;
+-----+------+---------------------+------------+---------+------+----------+-------------------+
| Id  | User | Host                | db         | Command | Time | State    | Info              |
+-----+------+---------------------+------------+---------+------+----------+-------------------+
| 489 | root | xx.xx.xx.xx:8791 | distribute | Query   |    5 | updating | update product SET product_name = '测试商品', price = 5.00, |
```

[参考资料：关于MySQL的lock wait timeout exceeded解决方案](https://blog.csdn.net/weixin_34166472/article/details/88805074?utm_medium=distribute.pc_relevant.none-task-blog-BlogCommendFromBaidu-1.control&depth_1-utm_source=distribute.pc_relevant.none-task-blog-BlogCommendFromBaidu-1.control)

### 超卖现象一

com.example.distributedemo.service.OrderService.createOrder 扣减库存代码片段
```java
// 计算剩余库存
int leftCount = currentCount - purchaseProductNum;
// 更新库存
product.setCount(leftCount);
product.setUpdateUser("xxx");
product.setUpdateTime(new Date());
Thread.sleep(3000);
productMapper.updateByPrimaryKeySelective(product);
```

从上述代码片段可以看出, 如果在并发线程情况下, 会出现超卖情况. 将这些危险操作, 交给MySQL, 在每次更新操作时候都会有行锁保证.

<br>

屏蔽上述代码片段, 独立编写一个增量扣减库存的数据库操作方法
```java
// 不推荐代码扣减库存, 这里使用数据库去扣减, 数据库有行锁, 避免并发问题
productMapper.updateProductCount(purchaseProductNum,
        "xxx",
        new Date(),
        product.getId()
);
```

com.example.distributedemo.dao.ProductMapper.updateProductCount
```java
int updateProductCount(@Param("purchaseProductNum") int purchaseProductNum,
                       @Param("updateUser") String xxx, @Param("updateTime") Date date,
                       @Param("id") Integer id);
```

src/main/resources/mybatis/ProductMapper.xml
```xml
  <update id="updateProductCount">
    update product
    set count = count - #{purchaseProductNum,jdbcType=INTEGER},  <!-- 减去购买商品数量-->
    update_user = #{updateUser,jdbcType=VARCHAR},
    update_time = #{updateTime,jdbcType=TIME}
    where id = #{id,jdbcType=INTEGER}
  </update>
```