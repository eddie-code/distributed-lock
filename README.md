[TOC]

# distributed-lock

## distribute-demo （单体项目）

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

### 超卖现象二

从第一种超卖现象, 可以看出数据库出现库存负数递增的情况. 需要再次请求才会抛出异常 "商品xx仅剩余-4件, 无法购买"

以上这种情况：
- 校验库存、扣减库存统一加锁
- 使之成为原子性的操作
- 并发时，只有获得锁的线程才能校验、扣减库存
- 扣减库存结束后

解决办法：
- 扣减库存结束后，释放锁
- 确保库存不会扣成负数
- 使用Java原始锁, Synchronized解决

#### Synchronized 方法锁

<details>
<summary>Spring事务</summary>

com.example.distributedemo.service.OrderService.createOrder
```java
    @Transactional(rollbackFor = Exception.class)
    public synchronized Integer createOrder() throws Exception {

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
```

打印结果：
```properties
pool-1-thread-1库存数：1
pool-1-thread-2库存数：0
订单ID：[15]
pool-1-thread-3库存数：0
java.lang.Exception: 商品[100100]仅剩余[0]件, 无法购买
	at com.example.distributedemo.service.OrderService.createOrder(OrderService.java:65)
```

</details>

<br>

<details>
<summary>手动事务</summary>

com.example.distributedemo.service.OrderService.createOrder
```java
    /* 手动事务 */
    @Autowired
    private PlatformTransactionManager platformTransactionManager;

    /* 手动事务 */
    @Autowired
    private TransactionDefinition transactionDefinition;

//    @Transactional(rollbackFor = Exception.class)
    public synchronized Integer createOrder() throws Exception {

        /* 开启 - 手动事务 */
        TransactionStatus transactionStatus = platformTransactionManager.getTransaction(transactionDefinition);

        Product product = productMapper.selectByPrimaryKey(purchaseProductId);

        if (product == null) {
            /* 手动事务回滚 */
            platformTransactionManager.rollback(transactionStatus);
            throw new Exception("购买商品：" + purchaseProductId + "不存在");
        }

        /* =================计算库存开始================= */

        // 商品当前库存
        Integer currentCount = product.getCount();
        System.out.println(Thread.currentThread().getName() + "库存数：" + currentCount);

        // 校验库存 （购买数量 大于 商品数量）
        if (purchaseProductNum > currentCount) {
            /* 手动事务回滚 */
            platformTransactionManager.rollback(transactionStatus);
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

        // 检索商品的库存

        // 如果商品库存为负数, 抛出异常

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

        /* 手动事务提交 */
        platformTransactionManager.commit(transactionStatus);
        return order.getId();
    }
```

打印结果：
```properties
pool-1-thread-5库存数：1
订单ID：[16]
pool-1-thread-4库存数：0
java.lang.Exception: 商品[100100]仅剩余[0]件, 无法购买
	at com.example.distributedemo.service.OrderService.createOrder(OrderService.java:81)
	at com.example.distributedemo.service.OrderServiceTests.lambda$testConcurrentOrder$0(OrderServiceTests.java:47)
	at java.util.concurrent.ThreadPoolExecutor.runWorker(ThreadPoolExecutor.java:1149)
	at java.util.concurrent.ThreadPoolExecutor$Worker.run(ThreadPoolExecutor.java:624)
	at java.lang.Thread.run(Thread.java:748)
pool-1-thread-3库存数：0
java.lang.Exception: 商品[100100]仅剩余[0]件, 无法购买
	at com.example.distributedemo.service.OrderService.createOrder(OrderService.java:81)
	at com.example.distributedemo.service.OrderServiceTests.lambda$testConcurrentOrder$0(OrderServiceTests.java:47)
	at java.util.concurrent.ThreadPoolExecutor.runWorker(ThreadPoolExecutor.java:1149)
	at java.util.concurrent.ThreadPoolExecutor$Worker.run(ThreadPoolExecutor.java:624)
	at java.lang.Thread.run(Thread.java:748)
pool-1-thread-1库存数：0
java.lang.Exception: 商品[100100]仅剩余[0]件, 无法购买
	at com.example.distributedemo.service.OrderService.createOrder(OrderService.java:81)
	at com.example.distributedemo.service.OrderServiceTests.lambda$testConcurrentOrder$0(OrderServiceTests.java:47)
	at java.util.concurrent.ThreadPoolExecutor.runWorker(ThreadPoolExecutor.java:1149)
	at java.util.concurrent.ThreadPoolExecutor$Worker.run(ThreadPoolExecutor.java:624)
	at java.lang.Thread.run(Thread.java:748)
pool-1-thread-2库存数：0
java.lang.Exception: 商品[100100]仅剩余[0]件, 无法购买
	at com.example.distributedemo.service.OrderService.createOrder(OrderService.java:81)
	at com.example.distributedemo.service.OrderServiceTests.lambda$testConcurrentOrder$0(OrderServiceTests.java:47)
	at java.util.concurrent.ThreadPoolExecutor.runWorker(ThreadPoolExecutor.java:1149)
	at java.util.concurrent.ThreadPoolExecutor$Worker.run(ThreadPoolExecutor.java:624)
	at java.lang.Thread.run(Thread.java:748)
```

> 测试后数据需要更改回来: "UPDATE `distribute`.`product` SET `count` = 1 WHERE `id` = 100100"

</details>


#### Synchronized 块锁

<details>
<summary>手动事务</summary>

com.example.distributedemo.service.OrderService.createOrder
```java
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
```

打印结果：
```properties
pool-1-thread-1库存数：1
订单ID：[18]
pool-1-thread-2库存数：0
java.lang.Exception: 商品[100100]仅剩余[0]件, 无法购买
	at com.example.distributedemo.service.OrderService.createOrder(OrderService.java:85)
	at com.example.distributedemo.service.OrderServiceTests.lambda$testConcurrentOrder$0(OrderServiceTests.java:47)
	at java.util.concurrent.ThreadPoolExecutor.runWorker(ThreadPoolExecutor.java:1149)
	at java.util.concurrent.ThreadPoolExecutor$Worker.run(ThreadPoolExecutor.java:624)
	at java.lang.Thread.run(Thread.java:748)
pool-1-thread-5库存数：0
java.lang.Exception: 商品[100100]仅剩余[0]件, 无法购买
	at com.example.distributedemo.service.OrderService.createOrder(OrderService.java:85)
	at com.example.distributedemo.service.OrderServiceTests.lambda$testConcurrentOrder$0(OrderServiceTests.java:47)
	at java.util.concurrent.ThreadPoolExecutor.runWorker(ThreadPoolExecutor.java:1149)
	at java.util.concurrent.ThreadPoolExecutor$Worker.run(ThreadPoolExecutor.java:624)
	at java.lang.Thread.run(Thread.java:748)
pool-1-thread-4库存数：0
java.lang.Exception: 商品[100100]仅剩余[0]件, 无法购买
	at com.example.distributedemo.service.OrderService.createOrder(OrderService.java:85)
	at com.example.distributedemo.service.OrderServiceTests.lambda$testConcurrentOrder$0(OrderServiceTests.java:47)
	at java.util.concurrent.ThreadPoolExecutor.runWorker(ThreadPoolExecutor.java:1149)
	at java.util.concurrent.ThreadPoolExecutor$Worker.run(ThreadPoolExecutor.java:624)
	at java.lang.Thread.run(Thread.java:748)
pool-1-thread-3库存数：0
java.lang.Exception: 商品[100100]仅剩余[0]件, 无法购买
	at com.example.distributedemo.service.OrderService.createOrder(OrderService.java:85)
	at com.example.distributedemo.service.OrderServiceTests.lambda$testConcurrentOrder$0(OrderServiceTests.java:47)
	at java.util.concurrent.ThreadPoolExecutor.runWorker(ThreadPoolExecutor.java:1149)
	at java.util.concurrent.ThreadPoolExecutor$Worker.run(ThreadPoolExecutor.java:624)
	at java.lang.Thread.run(Thread.java:748)
```

> 测试后数据需要更改回来: "UPDATE `distribute`.`product` SET `count` = 1 WHERE `id` = 100100"

</details>


#### ReentrantLock锁 （并发包中的锁）

<details>
<summary>ReentrantLock锁+手动事务</summary>

com.example.distributedemo.service.OrderService.createOrder
```java
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

    /* Java并发包的类 */
    private Lock lock = new ReentrantLock();

//    @Transactional(rollbackFor = Exception.class)
    public Integer createOrder() throws Exception {

        Product product = null;

        lock.lock();

        try {
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
        }finally {
            // 不管是否抛出异常, 都必需要释放锁
            lock.unlock();
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
```

打印结果：
```properties
pool-1-thread-3库存数：1
pool-1-thread-4库存数：0
java.lang.Exception: 商品[100100]仅剩余[0]件, 无法购买
	at com.example.distributedemo.service.OrderService.createOrder(OrderService.java:91)
	at com.example.distributedemo.service.OrderServiceTests.lambda$testConcurrentOrder$0(OrderServiceTests.java:47)
	at java.util.concurrent.ThreadPoolExecutor.runWorker(ThreadPoolExecutor.java:1149)
	at java.util.concurrent.ThreadPoolExecutor$Worker.run(ThreadPoolExecutor.java:624)
	at java.lang.Thread.run(Thread.java:748)
pool-1-thread-5库存数：0
java.lang.Exception: 商品[100100]仅剩余[0]件, 无法购买
	at com.example.distributedemo.service.OrderService.createOrder(OrderService.java:91)
	at com.example.distributedemo.service.OrderServiceTests.lambda$testConcurrentOrder$0(OrderServiceTests.java:47)
	at java.util.concurrent.ThreadPoolExecutor.runWorker(ThreadPoolExecutor.java:1149)
	at java.util.concurrent.ThreadPoolExecutor$Worker.run(ThreadPoolExecutor.java:624)
	at java.lang.Thread.run(Thread.java:748)
pool-1-thread-1库存数：0
java.lang.Exception: 商品[100100]仅剩余[0]件, 无法购买
	at com.example.distributedemo.service.OrderService.createOrder(OrderService.java:91)
	at com.example.distributedemo.service.OrderServiceTests.lambda$testConcurrentOrder$0(OrderServiceTests.java:47)
	at java.util.concurrent.ThreadPoolExecutor.runWorker(ThreadPoolExecutor.java:1149)
	at java.util.concurrent.ThreadPoolExecutor$Worker.run(ThreadPoolExecutor.java:624)
	at java.lang.Thread.run(Thread.java:748)
pool-1-thread-2库存数：0
java.lang.Exception: 商品[100100]仅剩余[0]件, 无法购买
	at com.example.distributedemo.service.OrderService.createOrder(OrderService.java:91)
	at com.example.distributedemo.service.OrderServiceTests.lambda$testConcurrentOrder$0(OrderServiceTests.java:47)
	at java.util.concurrent.ThreadPoolExecutor.runWorker(ThreadPoolExecutor.java:1149)
	at java.util.concurrent.ThreadPoolExecutor$Worker.run(ThreadPoolExecutor.java:624)
	at java.lang.Thread.run(Thread.java:748)
订单ID：[19]

```

> 测试后数据需要更改回来: "UPDATE `distribute`.`product` SET `count` = 1 WHERE `id` = 100100"

</details>

## distribute-lock （分布式锁）

### 单体应用锁存在的问题
- 单体应用拿到锁, 其余线程可以继续等待完成, 获取锁.
- 若一个A服务, 通过Nginx转发部署多个A服务情况下, 锁该如何分配？

#### 演示代码

<details>
<summary>点击查看</summary>

1. 创建新项目 distribute-lock
1. 添加依赖
1. 创建DemoController类
1. 使用POSTMAN测试单体应用“锁”情况
1. 使用IDEA模拟多服务启动“锁”情况 (会发现不同服务, 使用自己的线程. 没有达到分布式锁)

```java
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>
    <parent>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-parent</artifactId>
        <version>2.1.7.RELEASE</version>
        <relativePath/> <!-- lookup parent from repository -->
    </parent>
    <groupId>com.example</groupId>
    <artifactId>distribute-lock</artifactId>
    <version>0.0.1-SNAPSHOT</version>
    <name>distribute-lock</name>
    <description>Demo project for Spring Boot</description>

    <properties>
        <java.version>1.8</java.version>
    </properties>

    <dependencies>
<!--        <dependency>-->
<!--            <groupId>org.springframework.boot</groupId>-->
<!--            <artifactId>spring-boot-starter-data-jpa</artifactId>-->
<!--        </dependency>-->
<!--        <dependency>-->
<!--            <groupId>org.springframework.boot</groupId>-->
<!--            <artifactId>spring-boot-starter-data-redis</artifactId>-->
<!--        </dependency>-->
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-web</artifactId>
        </dependency>
<!--        <dependency>-->
<!--            <groupId>org.mybatis.spring.boot</groupId>-->
<!--            <artifactId>mybatis-spring-boot-starter</artifactId>-->
<!--            <version>2.1.0</version>-->
<!--        </dependency>-->
<!--        <dependency>-->
<!--            <groupId>mysql</groupId>-->
<!--            <artifactId>mysql-connector-java</artifactId>-->
<!--            <scope>runtime</scope>-->
<!--        </dependency>-->
        <dependency>
            <groupId>org.projectlombok</groupId>
            <artifactId>lombok</artifactId>
            <optional>true</optional>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-test</artifactId>
            <scope>test</scope>
        </dependency>
    </dependencies>

    <build>
        <plugins>
            <plugin>
                <groupId>org.springframework.boot</groupId>
                <artifactId>spring-boot-maven-plugin</artifactId>
            </plugin>
<!--            <plugin>-->
<!--                <groupId>org.mybatis.generator</groupId>-->
<!--                <artifactId>mybatis-generator-maven-plugin</artifactId>-->
<!--                <version>1.3.7</version>-->
<!--                <dependencies>-->
<!--                    <dependency>-->
<!--                        <groupId>mysql</groupId>-->
<!--                        <artifactId>mysql-connector-java</artifactId>-->
<!--                        <version>8.0.17</version>-->
<!--                    </dependency>-->
<!--                </dependencies>-->
<!--            </plugin>-->
        </plugins>
    </build>

</project>
```

```java
@Slf4j
@RestController
public class DemoController {

    private Lock lock = new ReentrantLock();

    @RequestMapping("singleLock")
    public String singleLock() {
        log.info("Entry method");
        lock.lock();
        try {
            log.info("Access lock");
            Thread.sleep(60000);
            System.out.println("线程名：" + Thread.currentThread().getName());
        } catch (InterruptedException e) {
            e.printStackTrace();
        } finally {
            lock.unlock();
        }
        return "success";
    }
}
```

> IDEA模拟多应用启动:  右上“Edit Configurations” 复制多个Spring Boot的启动 “Program arguments: --server.port=8081”

</details>