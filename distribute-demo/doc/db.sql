CREATE TABLE `order` (
  `id` int(11) NOT NULL AUTO_INCREMENT,
  `order_status` int(1) NOT NULL DEFAULT '1' COMMENT '订单状态：1待支付；',
  `receiver_name` varchar(255) NOT NULL COMMENT '收货人名称',
  `receiver_mobile` varchar(11) NOT NULL COMMENT '收货人手机号码',
  `order_amount` decimal(11,2) NOT NULL COMMENT '订单金额',
  `create_time` time NOT NULL,
  `create_user` varchar(255) NOT NULL,
  `update_time` time NOT NULL,
  `update_user` varchar(255) NOT NULL,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE `order_item` (
  `id` int(11) NOT NULL AUTO_INCREMENT,
  `order_id` int(11) NOT NULL COMMENT '订单id',
  `product_id` int(11) NOT NULL COMMENT '商品数量',
  `product_price` decimal(11,2) NOT NULL COMMENT '购买价格',
  `purchase_num` int(3) NOT NULL COMMENT '购买数量',
  `create_time` time NOT NULL,
  `create_user` varchar(255) NOT NULL,
  `update_time` time NOT NULL,
  `update_user` varchar(255) NOT NULL,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE `product` (
  `id` int(11) NOT NULL AUTO_INCREMENT,
  `product_name` varchar(255) NOT NULL COMMENT '商品名称',
  `price` decimal(11,2) NOT NULL COMMENT '价格',
  `count` int(5) NOT NULL COMMENT '数量',
  `product_desc` varchar(255) NOT NULL COMMENT '商品描述',
  `create_time` time NOT NULL,
  `create_user` varchar(255) NOT NULL,
  `update_time` time NOT NULL,
  `update_user` varchar(255) NOT NULL,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB AUTO_INCREMENT=100101 DEFAULT CHARSET=utf8mb4;

INSERT INTO `distribute`.`product`(`id`, `product_name`, `price`, `count`, `product_desc`, `create_time`, `create_user`, `update_time`, `update_user`) VALUES (100100, '测试商品', 5, 1, '测试商品', '15:14:43', 'xxx', '15:14:45', 'xxx')