# SecKill-Monolithic-Architecure

## 简介

简易在线秒杀 web 应用程序

用户可以在秒杀开启后进行秒杀活动；秒杀结束，阻止用户秒杀；用户成功秒杀到商品后阻止其二次秒杀；

演示：

![kCOT2j.gif](https://s2.ax1x.com/2019/01/20/kCOT2j.gif)

## 安装

用 IDEA open 本 maven 工程

在 jdbc.properties 里面修改数据库连接参数

在 sql 包下，执行 schema.sql ，建立数据库

启动 redis ，默认 host： 127.0.0.1，port ：6379；如需修改请在 spring-dao.xml 文件里面修改

添加 Tomcat，部署项目启动，访问连接：http://localhost:8080/seckill/list

## 特性

经典 SSM 架构的 Maven 工程

热点数据用 Redis 缓存

前后端采用 JSON 交互

数据库使用 MySQL

