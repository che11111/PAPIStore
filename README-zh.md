# PAPIStore

<img src="logo.png" width="300" height="300" alt="image">

## 插件作用

可以将玩家对应的 PAPI变量 储存在数据库中

每当玩家上线时，刷新一次数据

数据库中的数据可以用作例如QQ群机器人查询、网站查询玩家数据信息等

例如利用本插件可采集的数据：（MySQL）

| Player      | Money | Point |Death | Kills |
| -------- | -------- |-------- |-------- |-------- |
| Steve     | 100    | 15 | 9 | 5 |
| Alex  | 234       | 12 | 10 | 3 |


## 依赖插件

PlaceholderAPI 

https://github.com/PlaceholderAPI/PlaceholderAPI


## 可用指令 


1、玩家在线时，刷新自己的所有数据的指令
```
/papistore refresh

权限：papistore.refresh
```
2、管理员刷新某玩家数据的指令
```
/papistore refresh <玩家>

权限：papistore.op

举例：/papistore  refresh  Steve
```
3、管理员创建一个papi记录列
```
/papistore create <PAPI变量> <列名>

权限：papistore.op

举例：/papistore  create  %vault_eco_balance%  money
```
4、管理员生成某一列的所有玩家的数据
```
/papistore generate <列名>

权限：papistore.op

举例：/papistore  generate  money

```

## 数据库配置

仅支持MySQL数据库
配置项如下：
```
mysql:
  user: root
  password: "PleaseInput"
  address: 127.0.0.1
  port: 3306
  useSSL: false
  database: "minecraft"
  table: "papi_mysql"
  allowPublicKeyRetrieval: true
```
