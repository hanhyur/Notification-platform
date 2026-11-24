# 로컬 PostgreSQL 복제 실행

저장소 루트에서 기존 `DB_NAME`, `DB_USERNAME`, `DB_PASSWORD`와 복제 연결 전용 `DB_REPLICATION_PASSWORD`를 환경에 설정한 뒤 실행합니다. `replica` 프로필은 기존 Primary에 복제 역할을 준비하고 Standby가 별도 볼륨에서 데이터를 따라가게 합니다.

```sh
docker compose -f infra/compose.yaml --profile replica up -d postgres-replica
docker compose -f infra/compose.yaml --profile replica ps -a
```

Standby가 준비되면 두 서비스에서 실제 역할을 확인합니다. 순서대로 Primary는 `f`, Standby는 `t`를 반환합니다.

```sh
docker compose -f infra/compose.yaml exec -T postgres sh -c 'psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -Atc "SELECT pg_is_in_recovery()"'
docker compose -f infra/compose.yaml --profile replica exec -T postgres-replica sh -c 'psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -Atc "SELECT pg_is_in_recovery()"'
```

호스트에서 실행하는 백엔드는 기존 Primary 설정에 아래 Replica 주소를 함께 적용합니다. 애플리케이션은 기본적으로 두 DB에 같은 사용자로 접속합니다. 별도의 읽기 계정을 구성한 환경에서는 `DB_REPLICA_USERNAME`과 `DB_REPLICA_PASSWORD`를 사용할 수 있습니다.

```sh
export DB_REPLICA_URL="jdbc:postgresql://127.0.0.1:${DB_REPLICA_PORT:-5433}/${DB_NAME}"
```

쓰기 트랜잭션은 Primary(Master), 독립적인 `@Transactional(readOnly = true)` 조회는 Replica(Slave)를 사용합니다. 목록과 대시보드는 복제 지연만큼 늦게 갱신될 수 있습니다. 쓰기 도중 실행하는 조회, 등록·재시도 중복 판정, 발송 대기·선점 직후 조회, 상세와 발송·운영 이력은 최신값이 필요해 Primary를 사용합니다. `DB_REPLICA_URL`을 비워 두면 기존 단일 DB로 실행합니다.

`DB_SLOW_QUERY_THRESHOLD`의 기본값은 `500ms`입니다. 기준을 넘긴 SQL 실행은 `slow_query` 경고를 남기며, `SlowQueryAlertSink` 빈을 등록하면 같은 알림을 받을 수 있습니다. 쿼리 식별자는 호출 코드의 위치와 JDBC 메서드로 구성됩니다.
