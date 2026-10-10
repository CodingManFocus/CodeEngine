# Code Engine

Paper 타입을 직접 사용하는 `.ce` 모듈 컴파일 엔진입니다. **검증 대상은 Paper 1.21.11 build 132 / JDK 21**입니다. 다른 서버 버전 및 Folia 지원은 검증하지 않았습니다.

```text
.ce → 구조 파싱 / AST → Java 소스 → JDK javac → 모듈 JAR → 전용 ClassLoader
```

실행 중에는 구문 해석기가 없습니다. 이벤트와 명령은 컴파일된 메서드를 호출하며, `Player`, `World`, `Block`, `ItemStack`, `Material`과 이벤트 객체는 실제 Paper 객체입니다. JIT 최적화 대상인 일반 JVM 바이트코드로 실행됩니다. 이는 모든 코드의 성능이 일반 플러그인과 항상 같다는 보증은 아닙니다. 측정 범위와 결과는 [검증 보고서](docs/verification.md)를 참고하세요.

## 빌드와 설치

전체 **JDK 21**과 **Node.js 22 이상 (npm 포함)**을 설치하고 다음 명령을 실행합니다.

```bash
./gradlew clean build
```

Windows에서는 `gradlew.bat clean build`를 사용합니다. 빌드 의존성은 최초 빌드 시 다운로드됩니다. Gradle이 `npm ci`와 Studio 빌드를 실행하고 결과를 플러그인 JAR에 포함합니다. Node.js는 소스 빌드에만 필요하며, 배포 서버에는 필요하지 않습니다. 서버에서 모듈을 컴파일할 때 외부 컴파일러나 Kotlin 런타임을 다운로드하지 않습니다.

1. `codeengine-plugin/build/libs/codeengine-plugin-0.1.0.jar`를 서버의 `plugins/`에 넣습니다.
2. 서버를 JDK 21로 시작합니다. 플러그인 업데이트에는 서버 재시작을 사용합니다.
3. `plugins/CodeEngine/modules/hello.ce` 예제가 생성되고 기본 설정에서는 자동 로드됩니다.
4. `/cehello`를 실행합니다. 예제 권한 `codeengine.hello`는 OP 또는 권한 플러그인으로 부여합니다.

API JAR는 개발용입니다. 서버의 `plugins/`에는 플러그인 JAR 하나만 설치합니다. `verification` 및 `verification-external` JAR는 임시 테스트 서버 전용이며 배포 서버에 설치하지 않습니다.

### 모듈과 소스 파일

작성·빌드·로드·언로드하는 기능 단위의 이름은 **모듈**로 통일합니다. `.ce`는 **모듈 소스 파일**, 컴파일 결과는 **모듈 JAR**입니다. 현재 모듈 소스 파일 하나가 모듈 하나를 정의하며, `modules/welcome.ce`의 선언은 `module welcome;`이어야 합니다.

## GitHub Actions

`main`에 push하거나 `main` 대상 Pull Request를 열면 Ubuntu 24.04 / Temurin JDK 21에서 Gradle Wrapper를 검증하고 `./gradlew --no-daemon --stacktrace build`를 실행합니다. GitHub의 **Actions → Build → Run workflow**에서 수동으로 실행할 수도 있습니다.

성공한 실행의 **Artifacts**에서 `codeengine-build-<실행 번호>`를 다운로드할 수 있습니다. 플러그인·API JAR, 해당 커밋의 전체 소스 ZIP, 라이선스 문서와 SHA-256 체크섬이 포함됩니다. `codeengine-test-reports-<실행 번호>`에는 생성된 JUnit XML과 HTML 테스트 보고서를 보관하며, 테스트가 실패해도 업로드를 시도합니다. 두 아티팩트의 보관 기간은 7일입니다.

`main`의 push 또는 수동 실행이 성공하면 **Releases**에도 자동 배포합니다. 태그와 제목은 `YYYY-MM-DD-<커밋 해시 7자리>` 형식이며, 날짜는 커밋 시각을 한국 시간(`Asia/Seoul`)으로 변환한 값입니다. 예: `2026-09-27-bf27374`. 플러그인·API JAR, 전체 소스 ZIP, 라이선스 문서와 체크섬을 첨부합니다. 모든 파일 업로드가 끝난 뒤 공개하며, Release 파일에는 위의 7일 만료가 적용되지 않습니다. 같은 커밋을 재실행하면 이미 공개된 Release는 유지하고, 중단된 draft는 이어서 배포합니다. PR에서는 Release를 만들지 않습니다.

CI는 단위 테스트와 컴파일 통합 테스트를 실행하고 실서버 검증 도구도 빌드합니다. 실제 Paper 서버를 실행하는 회귀 테스트·벤치마크·브라우저 검증은 아래의 별도 재현 절차를 사용합니다.

## 구조

| 디렉토리 | 책임 |
|---|---|
| `codeengine-api` | `CodeModule`, `ModuleContext` 두 인터페이스. Paper 객체 추상화 없음 |
| `codeengine-compiler` | Lexer, Parser, AST, JavaEmitter, javac 호출, JAR 생성 |
| `codeengine-plugin` | Paper 생명주기, 모듈별 자원 소유권, 명령, 파일 저장, HTTP 서버 |
| `codeengine-webide` | React + TypeScript + CodeMirror 6 편집기. 외부 CDN 없이 JAR에서 제공 |
| `verification` | 실서버 회귀 테스트, Java 기준 구현, 측정 및 브라우저 검증 도구 |
| `verification-external` | 실제 무료 PlaceholderAPI 비교, 외부 이벤트·종료 검사, 원본 측정 자료 |
| `examples` | 바로 사용할 수 있는 `.ce` 예제 |

Java 패키지 루트는 `kr.codenamemc.codeengine`입니다. 변수·메서드는 camelCase, 클래스는 Java 관례의 PascalCase를 사용합니다.

## 최소 예제

`plugins/CodeEngine/modules/welcome.ce`:

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

## 외부 Java API

```java
module placeholders;
use me.clip.placeholderapi.PlaceholderAPI from "PlaceholderAPI";

command placeholders {
    if (sender instanceof Player player)
        sender.sendMessage(PlaceholderAPI.setPlaceholders(player, "%server_name%"));
    return true;
}
```

`from`에는 제공 플러그인의 정확한 `plugin.yml` 또는 `paper-plugin.yml` 이름을 적습니다. 같은 클래스명이 여러 플러그인에 있어도 명시한 제공자의 타입을 선택합니다. 공개 생성자·메서드·필드·외부 이벤트를 Java 타입 그대로 사용할 수 있습니다. API 서명에 필요한 별도 라이브러리 JAR도 연결하며, BetterModel의 `Semver`처럼 Paper 라이브러리 로더가 제공하는 타입도 원래 Class 객체를 유지합니다.
실행 중 호출마다 리플렉션이나 인수 변환을 추가하지 않습니다.
사용할 placeholder expansion은 별도 설치합니다. `onClose`는 독립 자원 정리를,
`onPluginClose`는 해당 제공자가 살아 있을 때만 가능한 API 정리를 등록합니다.

[문법·수명주기·지원 제한](docs/plugin-dependencies.md) · [무료 API 비교 검증](verification-external/README.md)

## 관리 명령

모든 관리 명령에는 `codeengine.admin` 권한이 필요하며 기본값은 OP입니다.

| 명령 | 동작 |
|---|---|
| `/ce list` | 실행 중인 모듈 목록 |
| `/ce build <id>` | 컴파일·타입 검사만 수행. 실행 상태 유지 |
| `/ce load <id>` | 미실행 모듈 컴파일 및 활성화 |
| `/ce reload <id>` | 기존 모듈 unload 완료 후 새 코드 컴파일·load. 실패하면 미실행 상태 유지 |
| `/ce unload <id>` | 모듈 비활성화 및 소유 자원 정리 |
| `codeengine webide` (또는 `ce webide`) | 서버 콘솔에서 WebIDE 세션 시작 |
| `/ce webstop` | WebIDE 종료 및 세션 폐기 |

전체 서버 `/reload`와 외부 플러그인 리로더는 지원하지 않습니다. `/ce reload`는 Code Engine이 소유한 모듈만 교체합니다.

## WebIDE

서버 콘솔에서 `codeengine webide`를 실행한 뒤 표시되는 링크를 엽니다. 기본 주소는 `http://127.0.0.1:17777`이며 실행할 때마다 새 세션 토큰이 생성됩니다. 토큰은 URL fragment로 전달된 후 주소창에서 제거되며 브라우저 저장소에 보관하지 않습니다. 페이지를 새로 고친 경우 콘솔 링크로 다시 연결합니다.

서버가 다른 PC에 있으면 SSH 포트 포워딩으로 접속합니다.

```bash
ssh -L 17777:127.0.0.1:17777 user@server
```

Studio는 React + CodeMirror 6 기반입니다. Code Engine 선언과 Java 본문의 문법 강조, 주석·문자열·Java text block 강조, 접기, 자동 들여쓰기, 괄호 자동 닫기, 실행 취소/다시 실행, 검색·치환, 다중 커서, 줄바꿈 설정을 제공합니다. 여러 모듈을 탭으로 열면 편집 내용·실행 취소 기록·커서·스크롤 위치를 탭별로 유지합니다. 새로고침 후에는 복구되지 않으며, 저장하지 않은 변경이 있으면 브라우저를 떠나기 전에 경고합니다.

탐색기의 **새 .ce 파일** 버튼으로 추가하고, 열린 파일 상단의 **이름 변경**과 **파일 삭제** 버튼으로 관리합니다. 이름 변경은 파일의 `module` 선언도 함께 수정하며, 먼저 저장해야 합니다. 실행 중인 모듈은 해제한 뒤 이름을 변경하거나 삭제할 수 있습니다. 삭제하면 저장하지 않은 편집 내용도 사라집니다.

| 단축키 | 기능 |
|---|---|
| Ctrl/Cmd+S | 현재 모듈 저장 |
| Ctrl/Cmd+Enter | 저장 후 빌드 검사 |
| Ctrl/Cmd+F | 검색·치환 패널 |
| Ctrl+Space | 타입·멤버·지역 변수·문법 스니펫 완성 |
| Ctrl+Shift+Space | 현재 호출의 매개변수·오버로드 안내 |
| Tab / Shift+Tab | 들여쓰기 / 내어쓰기, 스니펫 필드 이동 |
| Ctrl/Cmd+Z | 실행 취소 |

문법 가이드의 삽입 버튼은 모듈 끝에 선언 스니펫을 추가합니다. Studio에 연결하면 서버가 실행 버전에 대응하는 Paper API와 전이 의존성을 Maven 저장소에서 확보하고, Code Engine API와 Java 표준 API 자료를 함께 전달합니다. 최초 준비에는 인터넷이 필요하며, 준비된 서버 캐시는 오프라인에서도 재사용합니다. `1.21.11` 이하의 Paper SNAPSHOT은 같은 Minecraft 버전이어도 서버 빌드와 완전히 같다고 보장할 수 없으므로 화면에 표시합니다. 버전을 식별하지 못하거나 자료가 없으면 다른 Minecraft 버전으로 대체하지 않고 오류를 표시합니다.

**JAR 읽기·타입 인덱싱·소스 분석은 브라우저의 Web Worker에서 수행합니다.** 클래스·가져오기 완성, 이벤트 변수와 명령 매개변수, 상태·함수·지역 변수, 반환 타입을 통한 연쇄 호출, 상속된 멤버, 오버로드 안내, 타입·멤버 호버를 지원합니다. 서버는 타이핑에 따른 분석 요청을 받지 않습니다. JAR 캐시는 SHA-256으로 검증하며 브라우저 저장소에 소스나 세션 토큰은 저장하지 않습니다. 준비 실패 시 **API 다시 준비**를 누를 수 있고, 준비 중에도 편집·저장·빌드는 사용할 수 있습니다.

브라우저 분석은 완전한 Java 컴파일러가 아닙니다. 복잡한 Java 흐름·람다·제네릭 추론과 외부 플러그인의 `use ... from`은 분석 범위가 제한됩니다. 확인할 수 없는 타입에 대해서는 잘못된 오류를 단정하지 않습니다. Javadoc 본문은 제공하지 않으며 매개변수 이름은 JAR에 포함된 경우에 표시합니다. 최종 타입 검사는 서버의 **빌드 검사**를 사용합니다. 컴파일 오류는 문제 패널과 편집기 밑줄에 표시하고, 문제를 클릭하면 원본 줄로 이동합니다. 수정 후에는 이전 컴파일 오류 표시를 지우며 다시 빌드하여 확인합니다.

API 자료 준비·다운로드·파일 검증은 별도의 제한된 작업 큐에서 수행하고, JAR 전송은 HTTP 작업 스레드에서 수행합니다. 메인 서버 스레드는 이 작업들의 완료를 기다리지 않습니다. WebIDE 종료 시 준비 작업을 취소합니다. 구현과 지원 범위는 [WebIDE 코드 인텔리전스](docs/webide-intelligence.md)를 참고하세요.

서버 적용은 저장 후 로드/재로드를 요청하고, 해제는 현재 실행 중인 모듈을 중지합니다. 동시 편집으로 버전이 달라지면 HTTP 409로 저장을 거부하고 작성 중인 내용을 유지합니다. **로컬 사본 다운로드**로 보관한 뒤 **서버 파일 다시 열기**를 사용하여 변경을 병합하세요. 작업 중에는 편집·탭 전환을 잠가 저장한 소스와 빌드 진단이 엇갈리지 않게 합니다.

프런트엔드 개발 검증:

```bash
cd codeengine-webide
npm ci
npm test
npm run build
npx playwright install chromium
npm run test:browser
```

브라우저 테스트는 실제 CSP를 적용한 정적 서버와 API fixture에서 저장 충돌·탭 보존·진단 이동·모바일 레이아웃 등을 검증합니다. Java HTTP 서버와 리소스 패키징은 Gradle 테스트에서 확인하고, 실제 Paper 연동은 아래의 `verification --ui` 절차로 별도 확인합니다.

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
- 현재 한 파일이 한 모듈입니다. 모듈 의존성 그래프, 모듈 간 클래스 공유, 사용자 정의 최상위 클래스, 별도 식 문법, LSP 자동 완성은 제공하지 않습니다. `use`는 런타임 클래스패스에 있는 타입을 가져옵니다. Bukkit(`plugin.yml`)과 Paper(`paper-plugin.yml`) 플러그인의 API는 `use 타입 from "플러그인명";`으로 제공자를 지정합니다. `requires plugin`은 타입을 가져오지 않고 생존 의존성만 선언합니다. [지원 범위](docs/plugin-dependencies.md)를 확인하세요.

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

Copyright (c) 2026 CodingManFocus

Code Engine은 **GNU General Public License v3.0 (GPL-3.0-only)**으로 공개합니다. 전체 조건은 [LICENSE.md](LICENSE.md)를 확인하세요. 제3자 구성 요소의 라이선스는 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)를 참고하세요.
