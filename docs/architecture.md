# 구조와 교체 절차

## 역할 분리

- `Lexer`: 문자열·주석을 보존하는 위치 기반 토큰화.
- `Parser`: 선언 구조 검증과 AST 생성. Paper 호출 의미를 런타임에서 해석하지 않음.
- `JavaEmitter`: 타입과 호출을 Java로 직접 내보내고 소스 줄 번호 맵 생성.
- `ModuleCompiler`: JDK `JavaCompiler` 호출. annotation processor 비활성화, 명시적 클래스패스, 임시 출력 경로 및 실패 정리.
- `RuntimeClasspath`: 실제 서버/플러그인 로더의 JAR 위치 수집. 버전별 경로 하드코딩이나 원격 의존성 다운로드 없음.
- `ModuleSourceStore`: 모듈 소스 파일의 ID, 크기, symlink 검사, SHA-256 revision, atomic move 저장.
- `ModuleScope`: 리스너, native 명령, 반복 작업의 등록·해제 소유권.
- `ModuleManager`: 큐와 busy 상태, 스레드 전환, 로딩 및 실패 복구, ClassLoader 폐기.
- `EngineCommands`: 관리자 명령과 WebIDE 세션 생명주기.
- `WebSecurity` / `WebIdeServer` / `JobRegistry`: 요청 인증·라우팅·완료 상태 관리.
- `CodeEnginePlugin`: 설정·구성 요소 조립·시작 및 종료.

## 실행 경로

이벤트는 Paper 이벤트 디스패처 → 생성된 typed executor → 생성 메서드로 전달됩니다. 객체를 복사하거나 별도 DTO로 변환하지 않습니다. 명령은 Paper CommandMap → 단일 NativeCommand 구현 → Paper CommandExecutor → 생성 메서드이며 모듈 ID를 다시 검색하는 중앙 라우터는 없습니다. 내부 반복 계산은 Java 원시 타입 연산입니다. 모듈 로드 시 생성자는 리플렉션으로 한 번 호출합니다. 외부 API의 타입 서명은 컴파일 준비 단계에서 리플렉션으로 검사하며, API 클래스의 정적 초기화는 실행하지 않습니다.

## 외부 API 책임 분리

- `PluginDependencyRegistry`: 메인 스레드에서 명시된 제공자의 인스턴스·세대 스냅샷과 종료 감지.
- `DependencyClasspath` / `ClasspathIndex`: 컴파일 워커에서 JAR 인덱싱과 `use ... from`의 제공자·타입 선택.
- `ApiTypeGraph` / `ClassOrigin`: 선택된 API의 공개·protected 서명과 제네릭 타입 탐색, 실제 Class 동일성 및 원본 JAR 검증.
- `SelectedApiJar`: 선택된 클래스만 담은 컴파일 전용 임시 JAR 생성. 실행 모듈에는 포함하지 않음.
- `ResolvedClasspath` / `ModuleClassLoader`: 컴파일과 동일한 API 소유자에게 클래스 연결.
- `ResourceScope`: 역순 정리, 각 등록의 단일 실행, 실패 집계. 제공자 조건은 개별 자원에 적용.
- `ModuleDisposer`: 진입 차단 후 관리 콜백 완료를 기다리고 사용자 종료·자원·산출물 정리.
- `ScopedCommandExecutor` / `ScopedTask` / `ScopedEventExecutor`: 관리 콜백의 진입과 반환 추적.

제공자 연결 검사는 클래스 해석 시 수행합니다. API 메서드 호출은 일반 Java 바이트코드이며,
호출마다 레지스트리를 탐색하거나 리플렉션을 수행하지 않습니다.

## 로드 및 재로드

1. 모듈 ID별 busy 상태를 설정합니다. reload는 기존 모듈의 unload를 먼저 완료합니다.
2. 신규 콜백 진입을 차단하고 등록을 제거합니다. 진행 중 관리 콜백은 비차단 방식으로 기다립니다.
3. 메인 스레드에서 disable과 정리 작업을 시도하고, 로더·JAR를 닫습니다. 실패하면 reload를 중단합니다.
4. 컴파일 워커에서 소스를 읽고 파싱합니다. 메인 스레드에서 의존 제공자 스냅샷을 얻습니다.
5. 워커에서 클래스패스 검증과 javac 컴파일을 수행합니다. annotation processor와 암묵적 의존 소스 컴파일은 금지합니다.
6. 메인 스레드에서 제공자를 재확인하고 새 모듈을 생성·prepare·enable·activate합니다.
7. 활성화 시작 전부터 산출물 소유권을 등록하고 실행을 추적합니다. 생성자나 enable이 엔진 종료에 재진입해도 이중 정리하지 않습니다.
8. busy를 해제하고 외부 future를 완료합니다. 실패한 후보는 같은 disposer 경로로 정리합니다.

reload 중에는 모듈이 미실행 상태이며, 실패 시 이전 모듈을 복구하지 않습니다.
임의 월드·파일·외부 API 부작용은 자동 롤백하지 않습니다.

제공자 종료는 해당 모듈 진입점을 모두 제거한 뒤 정리를 시작합니다. 독립 disable/onClose는
제공자 종료만을 이유로 생략하지 않습니다. 엔진 종료 중 미완료 콜백이 있으면 사용자 훅을
생략하되, 산출물은 콜백이 반환할 때까지 유지합니다. 지원 경계는 [외부 API 계약](plugin-dependencies.md)을 참고하세요.

## 의도적으로 추가하지 않은 것

Paper 객체 래퍼, 자료형 문자열 변환 계층, 실행 시 AST 순회, 동적 속성 접근, 매 호출 리플렉션, 모든 이벤트를 수집하는 중앙 이벤트 버스, 자동 파일 감시·즉시 재로드, 영구 바이트코드 캐시를 두지 않았습니다. 이 선택은 실행 비용과 실패 원인의 수를 줄이기 위한 것입니다.
