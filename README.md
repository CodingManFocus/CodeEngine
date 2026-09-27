# Code Engine

Paper 타입을 직접 사용하는 `.ce` 모듈 컴파일 엔진입니다. **검증 대상은 Paper 1.21.11 build 132 / JDK 21**입니다. 다른 서버 버전 및 Folia 지원은 검증하지 않았습니다.

```text
.ce → 구조 파싱 / AST → Java 소스 → JDK javac → 모듈 JAR → 전용 ClassLoader
```

실행 중에는 구문 해석기가 없습니다. 이벤트와 명령은 컴파일된 메서드를 호출하며, `Player`, `World`, `Block`, `ItemStack`, `Material`과 이벤트 객체는 실제 Paper 객체입니다. JIT 최적화 대상인 일반 JVM 바이트코드로 실행됩니다. 이는 모든 코드의 성능이 일반 플러그인과 항상 같다는 보증은 아닙니다. 측정 범위와 결과는 [검증 보고서](docs/verification.md)를 참고하세요.

## 빌드와 설치

전체 **JDK 21**을 설치하고 다음 명령을 실행합니다.

```bash
./gradlew clean build
```

Windows에서는 `gradlew.bat clean build`를 사용합니다. 빌드 의존성은 최초 빌드 시 다운로드됩니다. 서버에서 모듈을 컴파일할 때 외부 컴파일러나 Kotlin 런타임을 다운로드하지 않습니다.

1. `codeengine-plugin/build/libs/codeengine-plugin-0.1.0.jar`를 서버의 `plugins/`에 넣습니다.
2. 서버를 JDK 21로 시작합니다. 플러그인 업데이트에는 서버 재시작을 사용합니다.
3. `plugins/CodeEngine/scripts/hello.ce` 예제가 생성되고 기본 설정에서는 자동 로드됩니다.
4. `/cehello`를 실행합니다. 예제 권한 `codeengine.hello`는 OP 또는 권한 플러그인으로 부여합니다.

API JAR는 개발용입니다. 서버의 `plugins/`에는 플러그인 JAR 하나만 설치합니다. `verification` JAR는 임시 테스트 서버 전용이며 배포 서버에 설치하지 않습니다.

## 구조

| 디렉토리 | 책임 |
|---|---|
| `codeengine-api` | `CodeModule`, `ModuleContext` 두 인터페이스. Paper 객체 추상화 없음 |
| `codeengine-compiler` | Lexer, Parser, AST, JavaEmitter, javac 호출, JAR 생성 |
| `codeengine-plugin` | Paper 생명주기, 모듈별 자원 소유권, 명령, 파일 저장, HTTP 서버 |
| `codeengine-webide` | 외부 CDN 없이 동작하는 독립 HTML/CSS/JavaScript 편집기 |
| `verification` | 실서버 회귀 테스트, Java 기준 구현, 측정 및 브라우저 검증 도구 |
| `examples` | 바로 사용할 수 있는 `.ce` 예제 |

Java 패키지 루트는 `kr.codenamemc.codeengine`입니다. 변수·메서드는 camelCase, 클래스는 Java 관례의 PascalCase를 사용합니다.

## 최소 예제

`plugins/CodeEngine/scripts/welcome.ce`:

```java
module welcome;

state int joins = 0;

on PlayerJoinEvent event {
    joins++;
    event.getPlayer().sendMessage(Component.text("환영합니다!"));
}

command welcome permission "server.welcome" {
    sender.sendMessage(Component.text("입장 횟수: " + joins));
    return true;
}
```

`/ce load welcome` 실행 후 `/welcome`이 일반 서버 명령으로 등록됩니다. `codeengine_welcome:welcome` 네임스페이스도 등록되며, 다른 명령을 덮어쓰는 대신 충돌을 거부합니다. 자세한 문법은 [언어 명세](docs/language.md)를 참고하세요.

## 관리 명령

모든 관리 명령에는 `codeengine.admin` 권한이 필요하며 기본값은 OP입니다.

| 명령 | 동작 |
|---|---|
| `/ce list` | 실행 중인 모듈 목록 |
| `/ce build <id>` | 컴파일·타입 검사만 수행. 실행 상태 유지 |
| `/ce load <id>` | 미실행 모듈 컴파일 및 활성화 |
| `/ce reload <id>` | 기존 모듈 unload 완료 후 새 코드 컴파일·load. 실패하면 미실행 상태 유지 |
| `/ce unload <id>` | 모듈 비활성화 및 소유 자원 정리 |
| `ce web` | 서버 콘솔에서 WebIDE 세션 시작 |
| `/ce webstop` | WebIDE 종료 및 세션 폐기 |

전체 서버 `/reload`와 외부 플러그인 리로더는 지원하지 않습니다. `/ce reload`는 Code Engine이 소유한 모듈만 교체합니다.

## WebIDE

서버 콘솔에서 `ce web`을 실행한 뒤 표시되는 링크를 엽니다. 기본 주소는 `http://127.0.0.1:17777`이며 실행할 때마다 새 세션 토큰이 생성됩니다. 토큰은 URL fragment로 전달된 후 주소창에서 제거되며 브라우저 저장소에 보관하지 않습니다. 페이지를 새로 고친 경우 콘솔 링크로 다시 연결합니다.

서버가 다른 PC에 있으면 SSH 포트 포워딩으로 접속합니다.

```bash
ssh -L 17777:127.0.0.1:17777 user@server
```

편집기에서 모듈 생성, 목록 조회, 저장, 빌드 검사, 서버 적용, 해제, 컴파일 진단을 사용할 수 있습니다. Ctrl/Cmd+S는 저장입니다. 서버 적용은 저장 후 로드/재로드를 요청합니다. 동시 편집으로 버전이 달라지면 HTTP 409로 저장을 거부합니다. 변경 내용을 따로 복사한 뒤 파일을 다시 열어 병합하세요.

## 안정성과 성능 경계

- 컴파일은 크기가 제한된 단일 작업 큐에서 수행합니다. Paper 등록·해제는 메인 스레드에서 실행합니다.
- 파일명과 모듈 ID는 일치해야 합니다. 소스는 모듈당 256 KiB 이하입니다.
- 빌드 결과마다 별도 디렉토리와 JAR를 사용합니다. 실패한 컴파일은 기존 JAR를 덮어쓰지 않습니다. 재사용 캐시는 두지 않습니다.
- reload는 unload와 load의 공통 경로를 순서대로 실행합니다. 기존 등록 해제·진행 중 이벤트 완료·disable·클래스로더 종료·빌드 정리가 끝난 뒤 소스 읽기와 컴파일을 시작합니다. 종료 또는 새 코드 로드에 실패하면 실패로 응답하며, 기존 모듈을 복구하지 않습니다. 종료 중 예외가 나도 나머지 정리는 시도합니다.
- reload 중 컴파일 시간에는 모듈이 미실행 상태입니다. 미실행 모듈에 reload를 실행하면 load와 같이 동작합니다. build는 실행 중인 모듈에 영향을 주지 않는 사전 검사입니다.
- 이벤트·명령·DSL 반복 작업은 모듈 소유로 관리합니다. 실패한 반복 작업은 로그를 남기고 해당 작업을 취소합니다.
- 상태 필드는 재로드 시 초기화됩니다. 보존할 상태는 `ctx.dataDirectory()` 아래에 직접 저장합니다.
- 임의의 Java/Paper 코드를 실행할 수 있는 **신뢰된 관리자용 코드 실행 환경**입니다. ClassLoader는 보안 샌드박스가 아닙니다. 무한 루프, JVM 종료, 외부 스레드, 사용자 코드의 과도한 메모리 사용을 엔진이 격리하지 않습니다.
- `enable`/`disable` 안의 월드 변경·파일 쓰기 등 임의 부작용은 자동 롤백할 수 없습니다. 가급적 짧고 예외에 안전하게 작성하세요.
- `ctx.plugin()`으로 직접 만든 작업·리스너, 외부 라이브러리에 등록한 콜백·스레드는 엔진이 추적할 수 없습니다. 모듈 교체가 필요하면 DSL 선언을 사용하고 직접 소유한 자원은 `disable`에서 해제하세요.
- 비동기 Paper 이벤트는 원래 실행 스레드에서 전달됩니다. 그 안에서 안전하지 않은 월드 API를 호출하면 안 됩니다. 엔진이 모든 이벤트를 메인 스레드로 강제 이동시키지 않습니다.
- 이벤트 등록에는 모듈별 전용 Listener 식별자를 사용합니다. ctx.listen에 같은 Listener 인스턴스를 전달해도 다른 모듈이나 외부 플러그인의 등록을 함께 해제하지 않습니다. EventExecutor에는 호출자가 전달한 원본 Listener와 원본 Event를 그대로 전달하며, priority와 ignoreCancelled는 Paper가 처리합니다. Paper의 등록 목록에는 엔진의 전용 Listener가 표시됩니다.
- unload는 새 이벤트 진입을 막고 등록을 해제한 뒤, 이미 진입한 관리 대상 이벤트가 반환할 때까지 비차단 방식으로 기다립니다. 대기 중인 모듈이 있을 때만 공유 작업 하나가 매 틱 완료를 확인합니다. 완료 후 메인 스레드에서 disable을 실행하고 클래스로더와 빌드 파일을 정리합니다. 일부 이벤트 등록 후 활성화에 실패한 후보에도 같은 규칙을 적용합니다.
- `unloadTimeoutSeconds`는 기본 10초이며 1~300초로 설정합니다. 시간 초과 시 작업은 실패하지만 클래스로더와 빌드 파일은 유지합니다. `/ce list`의 Stopping 목록에 남고 같은 모듈의 작업을 거부합니다. 콜백이 나중에 반환하면 자동 정리하며, 실패한 reload를 자동 재시작하지 않습니다. 정리 이후 명시적으로 load/reload하세요.
- 엔진/서버 종료 시에도 실행 중인 콜백 때문에 메인 스레드를 기다리게 하지 않습니다. 이 경우 해당 모듈의 disable을 건너뛰고 경고를 기록하며, 콜백 반환 후 클래스로더와 파일만 정리합니다. 콜백이 끝나지 않으면 JVM 종료까지 자원을 유지합니다. 종료 훅의 실행이 반드시 필요한 모듈은 서버 종료 전에 정상 unload를 완료하세요.
- 비동기 이벤트 진입·종료에는 원자적 카운터 비용이 있습니다. 동기 이벤트에도 상태 확인과 호출 수 기록 비용이 있으며, 비용 0을 보장하지 않습니다. 이벤트를 기다리는 동안 메인 스레드를 차단하지 않지만 사용자 enable/disable 코드 자체의 실행 시간은 메인 스레드에 영향을 줍니다.
- 현재 한 파일이 한 모듈입니다. 모듈 의존성 그래프, 모듈 간 클래스 공유, 사용자 정의 최상위 클래스, 별도 식 문법, LSP 자동 완성은 제공하지 않습니다. `use`는 런타임 클래스패스에 있는 타입을 가져옵니다. 다른 플러그인의 독립 ClassLoader API까지 자동 연결하지 않습니다.

## 검증 재현

```bash
./gradlew test
./gradlew :verification:jar :codeengine-plugin:jar
python3 verification/run-integration.py \
  --paper /absolute/path/paper.jar \
  --java /absolute/path/jdk-21/bin/java \
  --work /absolute/path/fresh-test-directory \
  --accept-eula --forks 3
```

이미 준비된 동일 Paper 서버의 `libraries`, `versions`, `cache`를 재사용하려면 `--prepared-server /path/to/prepared-server`를 지정합니다. 경로가 같은 파일시스템에 있어야 합니다. 원본 서버 월드·플러그인·설정은 복사하거나 변경하지 않습니다. 브라우저 검증은 Playwright/Chromium이 설치된 환경에서 `--ui`로 실행하며 `CODEX_PRIMARY_RUNTIME_NODE_MODULES`에 Playwright가 있는 node_modules 경로를 설정합니다. 별도 Chromium을 사용한다면 `CHROMIUM_PATH`에 실행 파일 경로를 지정합니다. `--ui-only --forks 1`은 측정 없이 브라우저 흐름만 검증합니다.

실험은 접속자 없는 격리 서버에서 수행합니다. 실제 플레이어 부하·네트워크·전체 TPS를 나타내는 벤치마크가 아닙니다.

## 라이선스

Code Engine은 **Sustainable Use License 1.0 (SUL-v1.0)**으로 공개합니다. 전체 조건은 [LICENSE.md](LICENSE.md)를 확인하세요. 제3자 구성 요소의 라이선스는 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)를 참고하세요.
