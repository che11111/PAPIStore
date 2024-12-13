# PAPIStore

![PAPIStore](logo.png)

### Plugin Function

It can store the corresponding PAPI variables of players in the database.

Whenever a player logs in, the data will be refreshed once.

The data in the database can be used for inquiries by QQ group robots, website inquiries about player data information, etc.

For example, the data that can be collected using this plugin (MySQL):

| Player      | Money | Point | Death | Kills |
| -------- | -------- |-------- |-------- |-------- |
| Steve     | 100    | 15 | 9 | 5 |
| Alex  | 234       | 12 | 10 | 3 |

[中文介绍](README-zh.md)

### Dependent Plugins

PlaceholderAPI 

https://github.com/PlaceholderAPI/PlaceholderAPI


### Available Commands

1. The command to refresh all of one's own data when the player is online:
```
/papimysql refresh

Permission: papimysql.refresh
```
2. The command for administrators to refresh a certain player's data:
```
/papimysql refresh <player>

Permission: papimysql.op

Example: /papimysql refresh Steve
```
3. The command for administrators to create a PAPI record column:
```
/papimysql create <PAPI variable> <column name>

Permission: papimysql.op

Example: /papimysql create %vault_eco_balance% money
```
4. The command for administrators to generate data for all players in a certain column:
```
/papimysql generate <column name>

Permission: papimysql.op

Example: /papimysql generate money
```

### Database Configuration

Only MySQL database is supported.
The configuration items are as follows:
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
