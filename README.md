# park-energy-data

园区能耗系统数据处理服务。它消费 `park-energy-access` 投递到 RabbitMQ 的 `raw_electric_data` 队列，完成设备测点解析、Redis 实时缓存、IoTDB 历史时序写入、MySQL 日统计和告警记录。

## 启动前依赖

- MySQL: `localhost:3306/park_energy_system`
- RabbitMQ: `localhost:5672`
- Redis: `localhost:6379`
- IoTDB: `localhost:6667`
- access 服务已启动，并能把模拟网关数据转发到 `raw_electric_data`

## 启动

```powershell
cd D:\Code\EC-EnergySys\park-energy-data
.\mvnw.cmd spring-boot:run
```

服务端口：`8102`

## 关键配置

默认配置在 `src/main/resources/application.yml`：

- RabbitMQ 队列：`raw_electric_data`
- RabbitMQ Exchange：`park.energy.exchange`
- RabbitMQ Routing Key：`data.raw`
- Redis 实时 Key：`realtime:device:{deviceId}`
- IoTDB 路径：`root.park_energy.device.d_{deviceId}`

如需覆盖配置，可以使用环境变量：

```powershell
$env:PARK_DB_USERNAME='root'
$env:PARK_DB_PASSWORD='你的MySQL密码'
$env:PARK_IOTDB_USERNAME='root'
$env:PARK_IOTDB_PASSWORD='root'
.\mvnw.cmd spring-boot:run
```

## API

```text
GET /api/data/realtime/devices/{deviceId}
GET /api/data/history?deviceId=1&pointCode=voltage_a&startTime=2026-07-18T00:00:00Z&endTime=2026-07-19T00:00:00Z
GET /api/data/statistics/daily?deviceId=1&pointCode=total_active_energy&startDate=2026-07-18&endDate=2026-07-18
POST /api/data/statistics/daily/rebuild?statDate=2026-07-18&deviceId=1
GET /api/data/alarms?deviceId=1&dealStatus=0
```

## 模拟网关数据映射

当前模拟网关上报格式是：

```json
{
  "meters": [
    {
      "deviceSn": "METER-0001",
      "registers": {
        "voltageA": 2201,
        "currentA": 102,
        "totalActiveEnergy": 123456
      }
    }
  ]
}
```

代码已兼容旧示例的 `$.points.*`，也兼容当前模拟网关的 `$.registers.*`。为了数据库配置更清晰，建议执行 `sql/data-demo-mapping.sql` 更新演示测点。
