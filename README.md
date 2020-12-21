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

### 数据库实现分布式锁 (悲观锁)

- 多个进程、多个线程访问共同组建数据库
- 通过 <font color="red">select ... from update</font> 访问同一条数据
- <font color="red">for update</font> 锁定数据, 其他线程只能等待

#### Navicat演示效果

建表
```sql
CREATE TABLE `distribute_lock` (
  `id` int(11) NOT NULL AUTO_INCREMENT,
  `business_code` varchar(255) NOT NULL COMMENT '区分不同业务使用的锁',
  `business_name` varchar(255) NOT NULL,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
```

插入测试数据
```sql
INSERT INTO `distribute`.`distribute_lock`(`business_code`, `business_name`) VALUES ('demo', 'demo演示')
```

分别在 Navicat for mysql 工具, 打开两个会话(查询), 设置会话不自动提交
```sql
select @@autocommit;

set @@autocommit=0;
```

在两个会话分别运行, 会发现后运行的会话窗口, 是查询不了数据出来的 (锁住了)
```sql
SELECT * FROM distribute_lock WHERE business_code = 'demo' FOR UPDATE;
```

需然在第一个会话窗口进行提交，才能解锁
```sql
COMMIT;
```

#### 代码演示

<details>
<summary>点击查看</summary>

<br>

1. tk-mybatis生成代码
1. application.yml 配置db
1. DistributeLockMapper 编写db操作方法
1. DistributeLockMapper.xml 编写sql

com.example.distributelock.dao.DistributeLockMapper
```java
DistributeLock selectDistributeLock(@Param("businessCode") String businessCode);
```

src/main/resources/mybatis/DistributeLockMapper.xml 使用 FOR UPDATE
```xml
<select id="selectDistributeLock" resultMap="BaseResultMap">
    SELECT * FROM distribute_lock
    WHERE business_code = #{businessCode,jdbcType=VARCHAR}
    FOR UPDATE
</select>
```

com.example.distributelock.controller.DemoController.singleLock
```java
@RequestMapping("singleLock")
@Transactional(rollbackFor = Exception.class)
public String singleLock() throws Exception {
    log.info("Entry method");
    // 检索demo的锁
    DistributeLock distributeLock = distributeLockMapper.selectDistributeLock("demo");
    if (distributeLock == null) {
        throw new Exception("分布式锁找不到");
    }
    log.info("Access lock");
    try {
        Thread.sleep(20000);
        System.out.println("时间：" + LocalDateTime.now() + " 线程名：" + Thread.currentThread().getName());
    } catch (InterruptedException e) {
        e.printStackTrace();
    }
    return "success";
}
```

> IDEA模拟多应用启动:  右上“Edit Configurations” 复制多个Spring Boot的启动 “Program arguments: --server.port=8081”

PostMan分别GET请求：
- http://localhost:8080/singleLock
- http://localhost:8081/singleLock

distribute-lock :8080/
```properties
17:15:31  INFO 23104 --- [nio-8080-exec-1] o.a.c.c.C.[Tomcat].[localhost].[/]       : Initializing Spring DispatcherServlet 'dispatcherServlet'
17:15:31  INFO 23104 --- [nio-8080-exec-1] o.s.web.servlet.DispatcherServlet        : Initializing Servlet 'dispatcherServlet'
17:15:31  INFO 23104 --- [nio-8080-exec-1] o.s.web.servlet.DispatcherServlet        : Completed initialization in 8 ms
17:15:31  INFO 23104 --- [nio-8080-exec-1] c.e.d.controller.DemoController          : Entry method
17:15:31  INFO 23104 --- [nio-8080-exec-1] c.e.d.controller.DemoController          : Access lock
时间：2020-12-15T17:15:51.447 线程名：http-nio-8080-exec-1
```

distribute-lock-8081 :8081/
```properties
17:15:32  INFO 18500 --- [nio-8081-exec-2] o.a.c.c.C.[Tomcat].[localhost].[/]       : Initializing Spring DispatcherServlet 'dispatcherServlet'
17:15:32  INFO 18500 --- [nio-8081-exec-2] o.s.web.servlet.DispatcherServlet        : Initializing Servlet 'dispatcherServlet'
17:15:32  INFO 18500 --- [nio-8081-exec-2] o.s.web.servlet.DispatcherServlet        : Completed initialization in 8 ms
17:15:32  INFO 18500 --- [nio-8081-exec-2] c.e.d.controller.DemoController          : Entry method
17:15:51  INFO 18500 --- [nio-8081-exec-2] c.e.d.controller.DemoController          : Access lock
时间：2020-12-15T17:16:11.460 线程名：http-nio-8081-exec-2
```

> 从时间可以看出, 8080 释放锁后,  8081才拿到锁, 线程等待.

<br>

**总结：**
- 优点：简单方便、易于理解、易于操作
- 缺点：并发量大时, 对数据库存在一定压力
- 建议：作为锁的数据库与业务数据库分开

</details>


### 基于Redis的Setnx实现分布式锁

#### 实现原理
- 获取锁的Redis命令
- SET resource_name my_random_value NX PX 30000
    - resource_name: 资源名称, 可根据不同的业务区分不同的锁
    - my_random_value：随机值, 每个线程的随机值都不同, 用于释放锁时的校验
    - NX：key不存在时设置成功, key存在则设置不成功
    - PX：自动失效时间, 出现异常情况, 锁可以过期失效
- 利用NX的原子性, 多个线程并发时, 只有一个线程可以设置成功
- 设置成功即获得锁, 可以执行后续的业务处理
- 如果出现异常, 过了锁的有效期, 锁自动释放
- 释放锁采用Redis的delete命令
- 释放锁时效验之前设置的随机数, 相同才能释放
- 释放锁的LUA脚本

##### LUA脚本
```shell script
if redis.call("get",KEYS[1]) == ARGV[1] then
    return redis.call("del",KEYS[1])
else
    return 0
end
```

##### 原理图解

```properties

A获得锁  A执行任务  锁过期                       A释放了B的锁
-------------------------------------------------------------------->
                            B获得锁  B处理业务
```

[参考：正确地使用Redis的SETNX实现锁机制](https://www.cnblogs.com/zh718594493/p/12111417.html)

#### 代码演示

<details>
<summary>点击查看</summary>

<br>

- 根据上述原理, 编写Redis分布式锁
- 定时任务集群部署, 任务重覆执行?
- 利用分布式锁解决重复执行的问题

pom.xml
```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-data-redis</artifactId>
</dependency>
```

application.yml
```yaml
logging:
  pattern:
    dateformat: HH:mm:ss
#  level:
#    com.example.distributedemo.dao: debug
mybatis:
  mapper-locations: /mybatis/*.xml
spring:
  datasource:
    password: 123456
    username: root
    url: jdbc:mysql://192.168.8.100:61337/distribute?serverTimezone=Asia/Shanghai&useSSL=false
  redis:
    host: 192.168.8.100
    port: 6379
```

com.example.distributelock.controller.RedisController.redisLock
```java
package com.example.distributelock.controller;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.connection.RedisStringCommands;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.data.redis.core.types.Expiration;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Arrays;
import java.util.List;
import java.util.UUID;

/**
 * @author eddie.lee
 * @ProjectName distributed-lock
 * @Package com.example.distributelock.controller
 * @ClassName RedisController
 * @description setNX，是set if not exists 的缩写，
                也就是只有不存在的时候才设置, 设置成功时返回 1 ， 设置失败时返回 0 。
                可以利用它来实现锁的效果，但是很多人在使用的过程中都有一些问题没有考虑到。
 * @date created in 2020-12-16 11:37
 * @modified by
 */
@Slf4j
@RestController
public class RedisController {

    @Autowired
    private RedisTemplate redisTemplate;

    @RequestMapping("redisLock")
    public String redisLock() {
        log.info("进入方法");
        String key = "eddieKey";
        String value = UUID.randomUUID().toString();

        RedisCallback<Boolean> redisCallback = connection -> {
            // 设置NX
            RedisStringCommands.SetOption setOption = RedisStringCommands.SetOption.ifAbsent();
            // 设置过期时间
            Expiration expiration = Expiration.seconds(30);

            // 序列化 key value
            byte[] eddieKey = redisTemplate.getKeySerializer().serialize(key);
            byte[] redisValue = redisTemplate.getKeySerializer().serialize(value);

            // 执行 setnx 操作
            assert eddieKey != null;
            assert redisValue != null;
            return connection.set(eddieKey, redisValue, expiration, setOption);
        };

        // 获取分布式锁
        Boolean b = (Boolean) redisTemplate.execute(redisCallback);
        if (b) {
            log.info("抢到锁了!");
            try {
                // 15s
                Thread.sleep(15000);
            } catch (InterruptedException e) {
                e.printStackTrace();
            } finally {
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

            }
        }

        log.info("success");
        return "success";
    }

}
```

PostMan同时请求：

http://localhost:8080/redisLock
```properties
14:28:54  INFO 21016 --- [nio-8080-exec-5] c.e.d.controller.RedisController         : 进入方法
14:28:54  INFO 21016 --- [nio-8080-exec-5] c.e.d.controller.RedisController         : 抢到锁了!
14:29:09  INFO 21016 --- [nio-8080-exec-5] c.e.d.controller.RedisController         : 释放锁结果：[true]
14:29:09  INFO 21016 --- [nio-8080-exec-5] c.e.d.controller.RedisController         : success
```

http://localhost:8081/redisLock
```properties
14:28:55  INFO 20692 --- [nio-8081-exec-4] c.e.d.controller.RedisController         : 进入方法
14:28:55  INFO 20692 --- [nio-8081-exec-4] c.e.d.controller.RedisController         : success
```

> 8080 和 8081 同时请求, 然后一个打印抢到锁, 另外一个没有打印就成功, 因为8080拿到锁后没有过期, 而8080锁释放了, 8081就直接成功


<br><br>

**《代码简化》**

com.example.distributelock.lock.RedisLock
```java
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
```


com.example.distributelock.controller.RedisController
```java
package com.example.distributelock.controller;

import com.example.distributelock.lock.RedisLock;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;


/**
 * @author eddie.lee
 * @ProjectName distributed-lock
 * @Package com.example.distributelock.controller
 * @ClassName RedisController
 * @description setNX，是set if not exists 的缩写，
 *              也就是只有不存在的时候才设置, 设置成功时返回 1 ， 设置失败时返回 0 。
 *              可以利用它来实现锁的效果，但是很多人在使用的过程中都有一些问题没有考虑到。
 * @date created in 2020-12-16 11:37
 * @modified by
 */
@Slf4j
@RestController
public class RedisController {

    @Autowired
    private RedisTemplate redisTemplate;

    @RequestMapping("redisLock")
    public String redisLock() {
        log.info("进入方法");

        // 传统写法
//        RedisLock redisLock = new RedisLock(redisTemplate, "eddieKey",30);
//        if (redisLock.getLock()) {
//            log.info("抢到锁了!");
//            try {
//                // 15s
//                Thread.sleep(15000);
//            } catch (InterruptedException e) {
//                e.printStackTrace();
//            } finally {
//                // **** implements AutoCloseable 就不需要finally 来释放锁****
//                boolean result = redisLock.unLock();
//                log.info("释放锁结果：[{}]", result);
//            }
//        }

        // jdk1.7之后添加的写法 try后面加入
        try (RedisLock redisLock = new RedisLock(redisTemplate, "eddieKey", 30)) {
            if (redisLock.getLock()) {
                log.info("抢到锁了!");
                Thread.sleep(15000);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }

        log.info("success");
        return "success";
    }
}
```

<br><br>

**《模拟分布式服务发送短信场景》**

- 模拟负载均衡下多台服务器, 定时发送短信给用户
    - 如何解决多台服务器同时发送短信呢?

按照上面代码继续模拟场景

com.example.distributelock.Application
```java
@EnableScheduling
```

com.example.distributelock.service.SchedulerService.sendSms
```java
package com.example.distributelock.service;

import com.example.distributelock.lock.RedisLock;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * @author eddie.lee
 * @ProjectName distributed-lock
 * @Package com.example.distributelock.service
 * @ClassName SchedulerService
 * @description
 * @date created in 2020-12-16 16:14
 * @modified by
 */
@Slf4j
@Service
public class SchedulerService {

    @Autowired
    private RedisTemplate redisTemplate;

    /**
     * 使用redis.setnx实现分布式锁：
     *      每5秒发送短信给 13800138000
     */
    @Scheduled(cron = "0/5 * * * * ?")
    public void sendSms() {
        try (RedisLock redisLock = new RedisLock(redisTemplate, "smsKey", 30)) {
            if (redisLock.getLock()) {
                log.info("向 13800138000 发送一条趣味短信! ");
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
```

8080
```properties
16:23:55  INFO 18200 --- [   scheduling-1] c.e.d.service.SchedulerService           : 向 13800138000 发送一条趣味短信! 
16:23:55  INFO 18200 --- [   scheduling-1] c.example.distributelock.lock.RedisLock  : 释放锁结果：[true]
16:24:00  INFO 18200 --- [   scheduling-1] c.example.distributelock.lock.RedisLock  : 释放锁结果：[false]
16:24:05  INFO 18200 --- [   scheduling-1] c.example.distributelock.lock.RedisLock  : 释放锁结果：[false]
```

8081
```properties
16:24:00  INFO 20828 --- [   scheduling-1] c.e.d.service.SchedulerService           : 向 13800138000 发送一条趣味短信! 
16:24:00  INFO 20828 --- [   scheduling-1] c.example.distributelock.lock.RedisLock  : 释放锁结果：[true]
16:24:05  INFO 20828 --- [   scheduling-1] c.e.d.service.SchedulerService           : 向 13800138000 发送一条趣味短信! 
16:24:05  INFO 20828 --- [   scheduling-1] c.example.distributelock.lock.RedisLock  : 释放锁结果：[true]
```

> 释放锁结果：[false] 就是没有抢到锁

</details>


### Zookeeper分布式锁代码实现

### 代码演示

<details>
<summary>点击查看</summary>

<br>

pom.xml
```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
    <parent>
        <artifactId>distributed-lock</artifactId>
        <groupId>com.example</groupId>
        <version>0.0.1-SNAPSHOT</version>
    </parent>
    <modelVersion>4.0.0</modelVersion>

    <artifactId>distribute-zk-lock</artifactId>

    <properties>
        <java.version>1.8</java.version>
    </properties>

    <dependencies>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-web</artifactId>
        </dependency>
        <dependency>
            <groupId>org.apache.zookeeper</groupId>
            <artifactId>zookeeper</artifactId>
            <version>3.6.2</version>
        </dependency>
        <dependency>
            <groupId>org.apache.curator</groupId>
            <artifactId>curator-recipes</artifactId>
            <version>4.2.0</version>
            <exclusions>
                <exclusion>
                    <artifactId>zookeeper</artifactId>
                    <groupId>org.apache.zookeeper</groupId>
                </exclusion>
            </exclusions>
        </dependency>
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
        <dependency>
            <groupId>junit</groupId>
            <artifactId>junit</artifactId>
            <scope>test</scope>
        </dependency>
    </dependencies>

    <build>
        <plugins>
            <plugin>
                <groupId>org.springframework.boot</groupId>
                <artifactId>spring-boot-maven-plugin</artifactId>
            </plugin>
        </plugins>
    </build>

</project>
```

com.example.distributezklock.lock.ZkLock (核心部分)
```java
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
```

com.example.distributezklock.ZkLockTests (单元测试, ZK是否对接成功)
```java
package com.example.distributezklock;

import com.example.distributezklock.lock.ZkLock;
import lombok.extern.slf4j.Slf4j;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.junit4.SpringRunner;

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
}
```

com.example.distributezklock.controller.ZookeeperController (模拟请求)
```java
package com.example.distributezklock.controller;

import com.example.distributezklock.lock.ZkLock;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * @author eddie.lee
 * @ProjectName distributed-lock
 * @Package com.example.distributezklock.controller
 * @ClassName ZookeeperController
 * @description
 * @date created in 2020-12-18 11:01
 * @modified by
 */
@Slf4j
@RestController
public class ZookeeperController {

    @RequestMapping("zkLock")
    public String zookeeperLock() {
        log.info("进入方法");
        try(ZkLock zkLock = new ZkLock()) {
            if (zkLock.getLock("order")) {
                log.info("抢到锁了! ");
                Thread.sleep(10000);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        log.info("方法已完成");
        return "方法已完成";
    }

}
```

PostMan请求：
- distribute-zk-lock :8080/
```properties
11:09:33  INFO 24584 --- [168.8.240:2181)] org.apache.zookeeper.ClientCnxn          : Session establishment complete on server 192.168.8.240/192.168.8.240:2181, session id = 0x101be5c5e960007, negotiated timeout = 40000
11:09:33  INFO 24584 --- [nio-8080-exec-5] c.e.d.controller.ZookeeperController     : 抢到锁了! 
11:09:43  INFO 24584 --- [nio-8080-exec-5] org.apache.zookeeper.ZooKeeper           : Session: 0x101be5c5e960007 closed
11:09:43  INFO 24584 --- [nio-8080-exec-5] c.example.distributezklock.lock.ZkLock   : 释放锁了! 
11:09:43  INFO 24584 --- [nio-8080-exec-5] c.e.d.controller.ZookeeperController     : 方法已完成
11:09:43  INFO 24584 --- [c-5-EventThread] org.apache.zookeeper.ClientCnxn          : EventThread shut down for session: 0x101be5c5e960007
```

- distribute-zk-lock-8081 :8081/
```properties
11:09:43  INFO 28200 --- [168.8.240:2181)] org.apache.zookeeper.ClientCnxn          : Socket connection established, initiating session, client: /192.168.8.88:3012, server: 192.168.8.240/192.168.8.240:2181
11:09:43  INFO 28200 --- [168.8.240:2181)] org.apache.zookeeper.ClientCnxn          : Session establishment complete on server 192.168.8.240/192.168.8.240:2181, session id = 0x101be5c5e960008, negotiated timeout = 40000
11:09:43  INFO 28200 --- [nio-8081-exec-2] c.e.d.controller.ZookeeperController     : 抢到锁了! 
11:09:53  INFO 28200 --- [nio-8081-exec-2] org.apache.zookeeper.ZooKeeper           : Session: 0x101be5c5e960008 closed
11:09:53  INFO 28200 --- [c-2-EventThread] org.apache.zookeeper.ClientCnxn          : EventThread shut down for session: 0x101be5c5e960008
11:09:53  INFO 28200 --- [nio-8081-exec-2] c.example.distributezklock.lock.ZkLock   : 释放锁了! 
11:09:53  INFO 28200 --- [nio-8081-exec-2] c.e.d.controller.ZookeeperController     : 方法已完成
```

> 可以看出 8080 抢到锁是在 11:09:33,  完成是 11:09:43. 而 8081 是在 11:09:43 抢到锁

</details>

### 基于Zookeeper的Curator客户端实现分布式锁 (简化版)

- 引入curator客户端
- curator已经实现了分布式锁的方法
- 直接调用即可

[Curator - 官方网站](http://curator.apache.org/)

#### 代码演示

<details>
<summary>点击查看</summary>

<br>

添加依赖
```xml
<dependency>
    <groupId>org.apache.curator</groupId>
    <artifactId>curator-recipes</artifactId>
    <version>4.2.0</version>
</dependency>
```

测试Curator是否能用
```java
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
```

在正式使用当中, 会把Curator设置@Bean形式
```java
@Bean(initMethod = "start", destroyMethod = "close")
public CuratorFramework getCuratorFramework() {
    RetryPolicy retryPolicy = new ExponentialBackoffRetry(1000, 3);
    CuratorFramework client = CuratorFrameworkFactory.newClient("192.168.8.240:2181", retryPolicy);
    return client;
}
```

控制层请求测试
```java
@RequestMapping("curatorLock")
public String curatorLock() {
    log.info("进入方法");
    InterProcessMutex lock = new InterProcessMutex(curatorFramework, "/order");
    try {
        if (lock.acquire(30, TimeUnit.SECONDS)) {
            log.info("抢到锁了!!");
            Thread.sleep(10000);
        }
    } catch (Exception e) {
        e.printStackTrace();
    }finally {
        try {
            lock.release();
            log.info("释放了Curator锁！");
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
    log.info("方法已完成");
    return "方法已完成";
}
```

PostMan请求：
- distribute-zk-lock :8080/
```properties
22:47:56  INFO 5204 --- [nio-8080-exec-2] c.e.d.controller.ZookeeperController     : 进入方法
22:47:56  INFO 5204 --- [nio-8080-exec-2] c.e.d.controller.ZookeeperController     : 抢到锁了!!
22:48:06  INFO 5204 --- [nio-8080-exec-2] c.e.d.controller.ZookeeperController     : 释放了Curator锁！
22:48:06  INFO 5204 --- [nio-8080-exec-2] c.e.d.controller.ZookeeperController     : 方法已完成
```

- distribute-zk-lock-8081 :8081/
```properties
22:47:57  INFO 18792 --- [nio-8081-exec-1] c.e.d.controller.ZookeeperController     : 进入方法
22:48:06  INFO 18792 --- [nio-8081-exec-1] c.e.d.controller.ZookeeperController     : 抢到锁了!!
22:48:16  INFO 18792 --- [nio-8081-exec-1] c.e.d.controller.ZookeeperController     : 释放了Curator锁！
22:48:16  INFO 18792 --- [nio-8081-exec-1] c.e.d.controller.ZookeeperController     : 方法已完成
```

</details>