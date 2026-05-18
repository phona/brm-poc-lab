# flowlong-app Helm Chart

ttpos 审批引擎（FlowLong 1.2.4 嵌入 Spring Boot 3）的 Kubernetes 部署 chart。

## 设计原则

1. **MySQL 不内置**——生产用云 RDS 或独立 MySQL 集群，K8s 不跑数据库
2. **DB 密码外置**——推荐 `existingSecret`，避免明文写进 values
3. **多副本 + 反亲和**——默认 2 副本，散到不同节点
4. **优雅停机**——`terminationGracePeriodSeconds: 30`，Spring Boot `shutdown: graceful` 处理在飞请求
5. **配置变更自动 rollout**——ConfigMap 内容 sha256 注入 pod annotation
6. **资源限制对齐实测**——PoC vm08 稳态 ~400 MiB，默认 limits 1Gi 余量 60%

## 前置准备

### 1. 准备 MySQL（一次性）

`flowlong` 库必须存在，schema 用仓库根 `docker/mysql/init/05-flowlong-schema.sql`：

```bash
mysql -h <RDS_HOST> -u root -p < docker/mysql/init/05-flowlong-schema.sql
```

授权一个非 root 用户给 chart 用：

```sql
CREATE USER 'flowlong'@'%' IDENTIFIED BY '<strong-password>';
GRANT ALL PRIVILEGES ON flowlong.* TO 'flowlong'@'%';
FLUSH PRIVILEGES;
```

### 2. 创建 DB 密码 Secret（推荐）

```bash
kubectl -n ttpos create secret generic flowlong-db \
  --from-literal=password='<strong-password>'
```

### 3. 推镜像到内网 registry

```bash
cd flowlong-app
docker build -t ghcr.io/phona/flowlong-app:1.2.4 .
docker push ghcr.io/phona/flowlong-app:1.2.4
```

## 安装

```bash
helm install flowlong ./charts/flowlong-app \
  -n ttpos --create-namespace \
  --set image.repository=ghcr.io/phona/flowlong-app \
  --set image.tag=1.2.4 \
  --set mysql.host=<RDS_HOST> \
  --set mysql.username=flowlong \
  --set mysql.existingSecret=flowlong-db
```

或者用 values 文件：

```yaml
# values-prod.yaml
image:
  repository: ghcr.io/phona/flowlong-app
  tag: "1.2.4"

mysql:
  host: ttpos-rds.cluster.example.com
  port: 3306
  database: flowlong
  username: flowlong
  existingSecret: flowlong-db

autoscaling:
  enabled: true
  minReplicas: 2
  maxReplicas: 6

ingress:
  enabled: true
  className: nginx
  hosts:
    - host: flowlong.internal.ttpos.com
      paths:
        - path: /
          pathType: Prefix
```

```bash
helm install flowlong ./charts/flowlong-app -n ttpos -f values-prod.yaml
```

## 验证

```bash
kubectl -n ttpos get pods -l app.kubernetes.io/instance=flowlong
kubectl -n ttpos logs -l app.kubernetes.io/instance=flowlong --tail=50 | grep "Started"
kubectl -n ttpos port-forward svc/flowlong-flowlong-app 8082:8082
curl http://localhost:8082/health   # → "ok"
```

## 升级（流程定义变更或镜像更新）

```bash
helm upgrade flowlong ./charts/flowlong-app -n ttpos -f values-prod.yaml
```

ConfigMap 内容变化会自动触发 RollingUpdate（pod template annotation `checksum/config`）。

## 卸载

```bash
helm uninstall flowlong -n ttpos
```

⚠️ MySQL 数据不会被删除（库是外置的）。

## values 参考

见 [`values.yaml`](./values.yaml)，全部字段有中文注释。常调字段：

| key | 说明 | 默认 |
|---|---|---|
| `replicaCount` | 固定副本数（关闭 HPA 时生效） | 2 |
| `image.repository` | 镜像地址 | ghcr.io/phona/flowlong-app |
| `image.tag` | 镜像 tag | "" → 用 Chart.appVersion = 1.2.4 |
| `mysql.host` | MySQL 主机 | mysql.default.svc.cluster.local |
| `mysql.existingSecret` | 已有 Secret（含 key: password） | "" |
| `jvm.opts` | JVM 启动参数 | `-Xms256m -Xmx512m -XX:+UseG1GC ...` |
| `resources.limits.memory` | 容器内存上限 | 1Gi |
| `autoscaling.enabled` | 开启 HPA | false |
| `ingress.enabled` | 开启 Ingress | false |

## 安全清单（生产前 checklist）

- [ ] DB 密码用 `existingSecret`，不写进 chart values 文件
- [ ] `image.tag` 固定到具体版本号，不用 `latest`
- [ ] `imagePullSecrets` 配好私有镜像仓库凭证
- [ ] `ingress` 加 TLS（如果对外暴露）
- [ ] `podSecurityContext.runAsNonRoot: true`（默认已开）
- [ ] `securityContext.allowPrivilegeEscalation: false`（默认已开）
- [ ] NetworkPolicy 限制只允许 ttpos main namespace 调用（chart 未内置，按集群规范单独写）

## 跟 ttpos 集成

集群内 ttpos main 调用 flowlong 的 base URL：

```
http://<release-name>-flowlong-app.<namespace>.svc.cluster.local:8082
```

例如 release=`flowlong`, namespace=`ttpos`：

```
http://flowlong-flowlong-app.ttpos.svc.cluster.local:8082
```

ttpos main 那边在 service/flow/ 里配置这个地址即可。
