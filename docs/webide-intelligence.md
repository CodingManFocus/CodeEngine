# WebIDE 코드 인텔리전스

## 책임과 스레드

| 구성 요소 | 실행 위치 | 책임 |
|---|---|---|
| `EngineCommands` | 서버 명령 스레드 | 실행 버전 문자열을 캡처하고 WebIDE 생명주기 관리 |
| `IntelligenceArtifacts` | 전용 `CodeEngine-API-Artifacts` 워커 | 준비 작업·재시도·캐시 검증·원자적 파일 게시 |
| `PaperApiVersion` / `MavenArtifactResolver` | API 워커 | 버전 좌표 선택, Maven 메타데이터·POM·BOM·전이 의존성 해석 및 다운로드 |
| `StandardApiArtifacts` | API 워커 | 내장 Code Engine API와 Java 표준 타입 자료 전달용 JAR 준비 |
| `WebIdeServer` | `CodeEngine-WebIDE` HTTP 작업 스레드 | 인증된 상태 JSON 및 등록된 JAR 바이트 전송 |
| `worker.ts` / `jar.ts` / `classfile.ts` | 브라우저 Web Worker | 자료 다운로드·캐시·해시 검증, 압축 해제, JVM 클래스 메타데이터 인덱싱 |
| `engine.ts` / `ceSource.ts` / `javaTypes.ts` | 브라우저 Web Worker | `.ce` 문맥·타입·상속 분석, 자동완성·호버·호출 서명·제한된 진단 |
| `client.ts` / `editor.ts` | 브라우저 UI | 비동기 Worker 요청, 편집기 제안·툴팁·밑줄 표시 |

서버에는 자동완성·호버·타이핑 진단 엔드포인트가 없습니다. JAR 클래스의 메서드나 사용자 소스를 서버에서 IDE용으로 인덱싱하지 않습니다. 기존 명시적 **빌드 검사**는 계속 서버의 컴파일 워커에서 javac를 사용합니다.

API 워커는 작업자 1개와 제한된 큐를 사용합니다. 메인 스레드에서 `join`, `get`, 다운로드, 해시 검증, JAR 작성·전송을 수행하지 않습니다. 종료는 작업 취소와 executor 종료 요청만 수행하며 완료 대기는 하지 않습니다. 백그라운드 작업도 CPU·메모리·디스크는 서버와 공유하므로 무제한 병렬 다운로드를 하지 않습니다.

## 버전과 캐시

- 공식 Paper의 최신 버전은 공개된 `<mc>.build.<build>-<channel>` 좌표를 사용합니다. Minecraft 버전이 다르면 거부합니다.
- 구버전은 `<mc>-R0.1-SNAPSHOT`을 사용합니다. 같은 Minecraft 버전의 API이며 실행 Paper 빌드와 정확히 일치한다는 의미는 아닙니다. Studio에 이 차이를 표시합니다.
- Maven 좌표를 식별할 수 없는 개발 빌드나 포크는 오류를 표시합니다. 임의의 최신 버전으로 조용히 대체하지 않습니다.
- Code Engine 컴파일 대상인 Java 21에 맞는 표준 타입을 제공합니다. JDK 21에서는 공개 패키지의 런타임 클래스 자료를 복사하며, 더 최신 JDK에서는 `ct.sym`의 Java 21 API 시그니처 자료를 사용합니다.
- 서버 캐시는 Paper·서버·JDK·Code Engine API·컴파일 대상의 식별자로 구분하고, SHA-256으로 파일을 다시 검사합니다. 캐시가 온전하면 네트워크 없이 준비할 수 있습니다. 손상된 자료는 재확보합니다.
- 브라우저 IndexedDB는 API JAR의 콘텐츠 해시를 키로 사용합니다. 소스와 토큰을 저장하지 않으며, 저장소를 사용할 수 없어도 세션 안에서는 동작합니다. 새 자료 준비 완료 후 사용하지 않는 JAR 캐시를 정리합니다.

서버 캐시 위치는 플러그인 데이터 폴더의 `webide-api-cache`입니다. 서버를 중지한 상태에서 이 폴더를 삭제하면 다음 연결 시 다시 확보합니다. SNAPSHOT 캐시는 같은 서버 식별자에서 재사용되며 매 접속마다 최신 SNAPSHOT으로 교체하지 않습니다.

## 인증된 자료 API

| 요청 | 결과 |
|---|---|
| `GET /api/intelligence` | 즉시 `loading`, `ready`, `error` 상태 JSON 반환. 최초 요청이 백그라운드 준비를 시작 |
| `POST /api/intelligence/retry` | 실패한 준비 작업을 다시 예약. 이미 진행 중이거나 준비됐다면 중복 시작하지 않음 |
| `GET /api/intelligence/artifact?id=<opaque-id>` | 준비 완료된 자료 중 등록된 ID만 JAR 바이트로 전송 |

기존 Bearer 인증·Host·Origin 검사를 적용합니다. 클라이언트가 Maven URL이나 파일 경로를 지정할 수 없습니다. 준비 완료 JSON에는 `version`, `requestedVersion`, `resolution`, `javaVersion`, `artifacts`가 포함됩니다. 각 artifact는 `id`, `name`, `sha256`, `size`, `url`을 가집니다. 실패는 재시도할 때까지 유지되며 편집·저장 자체를 막지 않습니다.

브라우저 Worker는 동일 출처의 고정된 파일로 번들되며 CSP의 `worker-src 'self'`로 실행합니다. 외부 CDN이나 원격 언어 서버를 사용하지 않습니다. 클래스 바이트코드는 실행하지 않습니다.

## 지원 범위

- 실제 JAR의 공개 타입·메서드·필드, 상속과 인터페이스, 정적·인스턴스 멤버 구분.
- `use` 타입 경로, 기본 import 타입, 이벤트 변수, 명령의 `sender`·`command`·`label`·`args`, `ctx`, 상태·함수·지역 변수.
- 알려진 반환 타입을 통한 연쇄 호출과 일부 제네릭 타입 치환, 오버로드 서명과 매개변수 이름, deprecated 표시.
- 브라우저에서 확실히 확인 가능한 가져오기·멤버 문제의 경고. 불완전한 타입 계층이나 지원하지 않는 표현식은 추측으로 오류 처리하지 않음.

완전한 javac/LSP가 아니며 복잡한 Java 제어 흐름·람다·패턴·캐스트·모든 제네릭 추론을 보장하지 않습니다. 외부 플러그인의 JAR는 이번 자료 묶음에 포함하지 않습니다. 따라서 `use ... from "Plugin"`은 해당 제공자 자료가 없는 한 불투명한 타입으로 취급하며 같은 이름의 Paper 타입으로 대신 분석하지 않습니다. 정의로 이동·리팩터링·Javadoc 본문은 제공하지 않습니다.

최근 Chromium/Firefox/Safari의 Web Worker, IndexedDB, Web Crypto, `DecompressionStream("deflate-raw")`를 사용합니다. ZIP64·암호화·분할 ZIP은 지원하지 않습니다. 자료 크기·압축 해제 크기·클래스 크기에 한도를 적용하고 지원하지 않는 형식은 명시적으로 실패합니다.

## 검증

`./gradlew build`는 Java의 버전 선택·Maven 전이 의존성·작업 스레드·캐시·취소·HTTP 인증/전송 테스트와 TypeScript 검사·JAR/언어 분석 단위 테스트를 실행합니다. `npm run test:browser`는 실제 javac JAR fixture로 Worker와 CSP, 상속 멤버 완성·호출 서명·진단, 브라우저 캐시, 손상된 다운로드, 준비 실패 후 재시도, 기존 편집 기능을 검증합니다. 테스트는 타이핑 후 분석용 네트워크 요청이 발생하지 않는지도 확인합니다.
