# Code Engine 언어 1

기능의 작성·실행·관리 단위는 **모듈**입니다. 모듈 소스 파일은 `plugins/CodeEngine/modules/<id>.ce`에 저장하며, 현재 파일 하나가 모듈 하나를 정의합니다. 컴파일 결과는 **모듈 JAR**라고 부릅니다.

확장자는 `.ce`입니다. 선언부는 자체 문법이며 각 `{ ... }` 본문, 필드 선언, 함수 매개변수와 타입은 **Java 21 문법**입니다. Java를 문자열로 대충 치환하지 않고 Lexer로 문자열·문자·주석·괄호를 구분해 선언 AST를 만든 뒤 Java 코드를 생성합니다. 타입 검사와 바이트코드 생성은 javac가 담당합니다.

## 선언 문법

```text
module <id>;
requires plugin "<PluginName>";
use <qualified.Type>;
state <Java field declaration>;
fn <name>(<Java parameters>) -> <Java return type> { <Java body> }
on <EventType> <variable> [priority <PRIORITY>] [ignoreCancelled] { <Java body> }
command <name> [permission "node.name"] { <Java body returning boolean> }
every <positive integer> ticks [after <positive integer> ticks] { <Java body> }
enable { <Java body> }
disable { <Java body> }
```

`module`은 파일의 첫 선언이어야 합니다. ID는 `[a-z][a-z0-9_]{0,47}`이고 `id.ce`와 일치해야 합니다. 모듈·명령 이름은 대소문자 별칭을 만들지 않습니다. `ce`, `codeengine` 명령과 `__ce`로 시작하는 식별자는 예약되어 있습니다. `enable`, `disable`은 각각 하나만 선언할 수 있습니다.

`state`는 private 인스턴스 필드로, `fn`은 private 메서드로 생성됩니다. 기본 타입·배열·Java 제네릭을 그대로 쓸 수 있습니다. 필드 초기화 시점에는 아직 `ctx`가 없으므로 Paper 준비 작업은 `enable`에서 합니다. 함수는 필요할 때 `throws` 문법 대신 본문에서 checked exception을 처리합니다. `enable`/`disable`은 checked exception을 허용합니다.

```java
state Map<UUID, Integer> scores = new HashMap<>();

fn score(UUID playerId) -> int {
    return scores.getOrDefault(playerId, 0);
}
```

문장 끝의 세미콜론은 Java와 같습니다. 지역 변수는 `var`를 사용할 수 있습니다. `let`, Kotlin 속성 접근, 타입 자동 변환, 문자열 보간 등은 지원하지 않습니다. Java 문자열·문자·text block·`//`·`/* */` 주석을 지원하며, Java의 선행 Unicode escape 처리는 금지합니다. 한국어 등 Unicode 문자를 원문 그대로 입력하세요. 선언 본문 중첩 한도는 128입니다.

## 기본 import

- `org.bukkit.*`, `org.bukkit.entity.*`, `org.bukkit.event.*`
- `org.bukkit.event.player.*`, `org.bukkit.event.block.*`
- `net.kyori.adventure.text.Component`, `java.util.*`

다른 타입은 `use org.bukkit.event.entity.EntityDamageEvent;`처럼 명시합니다. `use`에 wildcard와 static import는 허용하지 않습니다. 충돌하는 단순 이름은 완전한 클래스명을 씁니다.

외부 플러그인은 `requires plugin "PlaceholderAPI";`처럼 정확한 등록 이름을 선언한 뒤
`use`로 API 타입을 가져옵니다. 선언은 중복될 수 없고 런타임 API 호출 중계 코드로
변환되지 않습니다. [지원 범위와 생명주기](plugin-dependencies.md)를 확인하세요.

## 이벤트

```java
on BlockBreakEvent event priority HIGH ignoreCancelled {
    if (event.getBlock().getType() == Material.DIAMOND_BLOCK) {
        event.setCancelled(true);
    }
}
```

우선순위는 `LOWEST`, `LOW`, `NORMAL`(기본), `HIGH`, `HIGHEST`, `MONITOR`입니다. `ignoreCancelled`가 있으면 이미 취소된 이벤트를 건너뜁니다. Paper에 등록 가능한 이벤트 타입이어야 하며 원본 이벤트 인스턴스가 전달됩니다. 여러 이벤트 선언을 사용할 수 있습니다. 비동기 이벤트는 원래 스레드를 유지합니다.

직접 `ctx.listen`을 사용할 때 EventExecutor에는 전달한 원본 Listener와 원본 Event가 들어옵니다. Paper 등록과 해제에는 엔진의 모듈별 전용 Listener를 사용하므로, 여러 모듈이 같은 Listener를 공유해도 한 모듈의 해제가 다른 모듈에 영향을 주지 않습니다. Paper의 리스너 등록 목록에서 보이는 객체는 전용 Listener입니다.

## 명령

본문에 native `sender`, `command`, `label`, `args`가 제공됩니다. 명령은 boolean을 반환해야 합니다. 인수가 없거나 숫자가 아닐 가능성을 직접 처리하세요.

```java
command doublevalue permission "server.doublevalue" {
    if (args.length != 1) {
        sender.sendMessage("사용법: /doublevalue <정수>");
        return true;
    }
    try {
        sender.sendMessage(Long.toString(Long.parseLong(args[0]) * 2L));
    } catch (NumberFormatException error) {
        sender.sendMessage("정수를 입력하세요.");
    }
    return true;
}
```

권한을 생략하면 누구나 실행할 수 있습니다. 권한을 명시하면 Paper `Command.testPermission`을 사용합니다. Code Engine이 별도의 권한 관리 시스템을 만들지 않습니다. 명령별 인수 자동 완성은 현재 빈 목록을 반환합니다.

## 스케줄과 생명주기

```java
state long count = 0;
every 20 ticks after 1 ticks { count++; }
enable { ctx.plugin().getLogger().info("활성화"); }
disable { ctx.plugin().getLogger().info("비활성화"); }
```

주기 작업은 메인 스레드에서 실행되며 `after`를 생략하면 첫 지연은 주기와 같습니다. 지연과 주기는 양수 long 범위의 정수입니다. 성공적으로 등록된 후 다음 해당 틱부터 실행됩니다. 틱 지연은 실제 시계의 절대 시간 보증이 아닙니다.

`ctx`가 제공하는 값은 `plugin()`, `server()`, `dataDirectory()`입니다. `listen`, `command`, `every` 등록 메서드도 있지만 등록 준비·enable 단계에서만 허용됩니다. 생성기가 준비 단계에서 선언들을 등록하므로 대부분 직접 호출할 필요가 없습니다.

`ctx.onClose(AutoCloseable)`도 준비·enable 단계에서만 허용됩니다. 외부 등록 해제 작업을
등록하면 관리 대상 호출이 끝난 뒤 `disable` 다음에 역순으로 실행합니다. 의존 플러그인이
종료됐거나 엔진이 실행 중 콜백을 기다릴 수 없는 종료 상태라면 사용자 정리를 건너뛰고 경고합니다.

`reload`는 기존 모듈의 등록 해제, 이미 진입한 관리 대상 호출의 완료, `disable`, 외부 자원 정리, 클래스로더 종료가 끝난 뒤 새 소스를 읽고 컴파일하여 `prepare`/`enable`을 실행합니다. 실패한 경우 이전 모듈을 복구하지 않습니다. 기존 `disable`이 실패하면 새 컴파일/로드를 시작하지 않고 실패를 반환합니다. 따라서 컴파일하는 동안에도 모듈은 미실행 상태이며, 저장과 복원을 구현한 경우 이전 `disable`의 저장이 새 `enable`의 읽기보다 먼저 실행됩니다.

이벤트 완료를 기다리는 동안 메인 스레드를 차단하지 않습니다. `unloadTimeoutSeconds`(기본 10초, 1~300초)를 넘으면 작업을 실패로 반환하고 해당 모듈을 Stopping 상태로 유지합니다. 이벤트가 반환하기 전까지 클래스로더를 닫거나 새 인스턴스를 로드하지 않습니다. 나중에 완료되면 자동 정리하지만 실패한 reload는 재시작하지 않습니다. 이 추적은 DSL 이벤트·명령·반복 작업과 해당 `ctx` 등록에 적용하며 직접 생성한 스레드나 외부 API 콜백에는 적용하지 않습니다.

엔진/서버 종료 때 실행 중인 이벤트가 남으면 disable을 건너뛰고 경고를 기록합니다. 해당 콜백이 반환한 후 Paper API 호출 없이 클래스로더와 파일만 정리합니다. 반환하지 않는 콜백을 강제로 중단하거나 그 클래스로더를 강제로 닫지 않습니다.

## 진단

파서 오류는 원본 줄 번호를 표시합니다. javac 본문 오류도 `.ce`의 줄 번호로 매핑합니다. 생성기가 만든 선언/보조 코드에서 발생한 오류는 현재 1행으로 표시될 수 있습니다. 성공한 실행 모듈의 생성 소스와 JAR는 `plugins/CodeEngine/builds/<id>-<unique>/`에서 확인할 수 있으며, 해제·교체 시 정리됩니다. 단독 build 검사 산출물과 컴파일 실패 임시 파일은 즉시 정리합니다. 부분 활성화 실패 후 실행 중인 이벤트가 남은 후보의 산출물은 해당 콜백 반환까지 유지합니다.
