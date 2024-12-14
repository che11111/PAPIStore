# PAPIStore

<img src="logo.png" width="300" height="300" alt="image">

## Plugin Function

It can store the corresponding PAPI variables of players in the database.

Whenever a player logs in, the data will be refreshed once.

The data in the database can be used for inquiries by Discord robots, website inquiries about player data information, etc.

For example, the data that can be collected using this plugin (MySQL):

| Player      | Money | Point | Death | Kills |
| -------- | -------- |-------- |-------- |-------- |
| Steve     | 100    | 15 | 9 | 5 |
| Alex  | 234       | 12 | 10 | 3 |

[Chinese introduction](README-zh.md)

## Dependent Plugins

PlaceholderAPI 

https://github.com/PlaceholderAPI/PlaceholderAPI


## Available Commands

1. The command to refresh all of one's own data when the player is online:
```
/papistore refresh

Permission: papistore.refresh
```
2. The command for administrators to refresh a certain player's data:
```
/papistore refresh <player>

Permission: papistore.op

Example: /papistore refresh Steve
```
3. The command for administrators to create a PAPI record column:
```
/papistore create <PAPI variable> <column name>

Permission: papistore.op

Example: /papistore create %vault_eco_balance% money
```
4. The command for administrators to generate data for all players in a certain column:
```
/papistore generate <column name>

Permission: papistore.op

Example: /papistore generate money
```

## Database Configuration

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
