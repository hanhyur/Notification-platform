# 로컬 HTTPS와 운영자 세션 확인

운영자가 로그인하면 서버는 브라우저에 `SESSION` 쿠키를 줍니다. 브라우저는 다음 요청에 이 쿠키를 보내 같은 운영자로 조회합니다. 이 과정을 `https://localhost:8443`에서 확인할 수 있습니다. 테스트 클라이언트는 로컬에서 만든 공개 인증서로 서버의 신원을 확인합니다.

## 서버 실행

저장소 루트에서 시작합니다. JDK 21의 `keytool`, OpenSSL, Docker가 필요합니다. 기존 로컬 PostgreSQL의 `DB_NAME`, `DB_USERNAME`, `DB_PASSWORD`를 같은 터미널에 환경 변수로 설정합니다. 다른 포트를 사용하는 경우 `DB_PORT`도 설정합니다.

```sh
TLS_DIR=$(mktemp -d)
export LOCAL_TLS_PASSWORD="$(openssl rand -hex 24)"
bash infra/https/prepare-local-cert.sh "$TLS_DIR"
export LOCAL_TLS_KEYSTORE="file:$TLS_DIR/localhost.p12"
docker compose -f infra/compose.yaml up -d --wait postgres
(cd backend && SPRING_PROFILES_ACTIVE=local,local-https ./gradlew bootRun)
```

`local`은 기존 DB 연결 정보를 읽습니다. 뒤에 지정한 `local-https`는 같은 서버에 인증서와 HTTPS 포트를 적용합니다. `localhost.p12`에는 서버의 개인 키가 있고, `localhost.pem`은 클라이언트에 전달할 공개 인증서입니다. 스크립트가 출력한 디렉터리에 두 파일이 생성됩니다. 같은 인증서를 다시 쓸 때는 같은 비밀번호가 필요합니다. 인증서의 유효기간은 30일입니다. 만료된 인증서를 재사용하면 스크립트가 기존 파일을 보존하고 오류로 종료합니다. 이때는 새 디렉터리에서 발급한 뒤 서버와 클라이언트에 각각 새 파일의 경로를 지정합니다.

다른 터미널에서 `TLS_DIR`에 스크립트가 출력한 디렉터리의 절대 경로를 지정한 뒤 실행합니다. `200`이면 클라이언트가 인증서를 확인하고 서버에서 요청 확인용 토큰을 받아온 것입니다.

```sh
curl --cacert "$TLS_DIR/localhost.pem" -sS -o /dev/null -w '%{http_code}\n' https://localhost:8443/api/v1/auth/csrf
```

## 로그인부터 로그아웃까지 검증

저장소 루트에서 아래 명령을 실행합니다. 테스트가 임시 인증서, 운영자 계정, PostgreSQL 컨테이너와 서버 포트를 직접 준비합니다. Docker가 실행 중이면 별도로 서버를 띄울 필요가 없습니다.

```sh
(cd backend && ./gradlew test --tests '*LocalHttpsOperatorSessionIntegrationTest')
```

테스트는 먼저 공개 인증서를 신뢰하지 않는 클라이언트의 연결이 거부되는지 확인합니다. 인증서를 신뢰하는 클라이언트는 요청 확인용 토큰을 받아 `orders` 계정으로 로그인합니다. 그때 받은 쿠키로 본인 정보를 조회하고 로그아웃합니다. 로그아웃 응답의 쿠키에 `Max-Age=0`과 `Secure`가 붙고, 이전 쿠키를 다시 보내도 서버가 `401`을 반환해야 합니다.

## GitHub에서 확인할 결과

`develop` 또는 `main` 대상 PR과 두 브랜치의 push에서 GitHub Actions가 `bash infra/https/test-prepare-local-cert.sh`로 신규 발급, 유효한 인증서 재사용, 만료된 인증서 거부를 확인합니다. 이어 Java 21로 전체 테스트와 `bootJar` 빌드를 실행합니다. 성공하면 실행한 커밋 번호로 표시된 실행 JAR을 Actions 화면에서 내려받을 수 있습니다. AWS 자동 배포와 공개 도메인 연결은 배포 대상을 정한 뒤 진행합니다.
