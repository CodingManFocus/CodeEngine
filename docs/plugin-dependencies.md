# 외부 Java API

Code Engine 모듈은 설치된 Bukkit 플러그인의 공개 Java API를 직접 호출할 수 있습니다.
별도 애드온이나 API별 래퍼를 작성할 필요가 없습니다. `requires plugin`으로 제공자를
명시하고 `use`로 Java 타입을 가져옵니다.

```java
module placeholders;
requires plugin "PlaceholderAPI";
use me.clip.placeholderapi.PlaceholderAPI;

command placeholders {
    if (!(sender instanceof Player player)) return true;
    String result = PlaceholderAPI.setPlaceholders(player, "%server_name%");
    sender.sendMessage(result);
    return true;
}
```

해당 placeholder expansion은 별도로 설치해야 합니다. API 호출 기능 자체에는 유료
플러그인이 필요하지 않습니다. `examples/placeholders.ce`도 참고하세요.

## Java 호출 규칙

- `requires plugin "정확한 plugin.yml 이름";`은 설치·활성화된 제공자를 선언합니다.
- `use fully.qualified.ClassName;` 뒤 생성자, 정적/인스턴스 메서드, 공개 필드, 제네릭,
  익명 클래스, Java 인터페이스 구현을 일반 Java 21 문법으로 사용합니다.
- 오버로드 선택, 타입 변환, 접근 제한, checked exception은 javac가 검사합니다.
  모호한 호출은 캐스팅 등으로 명확하게 지정해야 합니다. private 접근 우회는 하지 않습니다.
- 제공 플러그인의 `Event` 하위 클래스도 `use`와 `on`으로 처리합니다.
- 컴파일 때 API 서명이 맞지 않으면 원본 `.ce` 줄 번호와 함께 실패합니다.
- `requires`는 Java 코드를 추가하지 않습니다. 실행 중 메서드 검색·리플렉션·인수 배열
  변환·API별 래퍼 호출 없이 일반 `invoke*` 바이트코드를 실행합니다.

비교한 Skript-reflect 소스는 [75ae3d6의 ExprJavaCall](https://github.com/SkriptLang/skript-reflect/blob/75ae3d6b7a43b4aba8326e426b3d24a46dfbe821/src/main/java/com/btk5h/skriptmirror/skript/reflect/ExprJavaCall.java)입니다.
해당 구현은 MethodHandle 후보 캐시·인수 변환·invokeWithArguments를 사용합니다.
Skript-reflect의 공개 Java API 접근 목적을 제공하되, 동적 표현식 문법이나 실행 중
오버로드 탐색을 재현하지 않습니다. Code Engine은 컴파일 방식에 맞는 Java 문법을 씁니다.

## 클래스 연결

컴파일 워커가 선언된 제공자의 실제 JAR(서버가 리매핑했다면 리매핑된 JAR)을 인덱싱합니다.
실행할 때 그 플러그인이 이미 사용 중인 **원래 클래스 로더**로 연결합니다. API 클래스를
모듈 JAR로 복사하거나 새로운 로더에서 중복 정의하지 않아 singleton·static 상태·타입
동일성을 유지합니다. 일반 API 호출마다 이 검사를 반복하지 않습니다.

중복 클래스, 미선언 플러그인 타입, 컴파일 때와 다른 JAR/로더는 오류로 처리합니다.
소스 파일이나 annotation processor를 의존 JAR에서 자동 실행하지 않습니다.

지원 범위는 Bukkit `plugin.yml`을 쓰는 JavaPlugin과 그 플러그인 JAR에 들어 있는 API입니다.
`paper-plugin.yml` 제공자, 별도 API/라이브러리 로더, manifest Class-Path, multi-release
제공 JAR은 현재 지원하지 않습니다. 관련 플러그인을 추가로 선언할 수 있지만, 두 JAR의
동일 바이너리 클래스 이름이 충돌하면 거부합니다. 임의 JAR 다운로드 기능은 없습니다.

## 종료와 정리

모듈은 독립적인 Bukkit Plugin이 아닙니다. `ctx.plugin()`은 CodeEngine입니다.
`requires`가 서버의 플러그인 시작/종료 그래프를 수정하지는 않습니다. 모듈 활성화 전에
제공자가 활성화되어 있어야 하며, 컴파일 중 상태가 바뀌면 활성화를 거부합니다.

제공자가 종료되면 해당 모듈의 관리 이벤트·명령·타이머 진입을 차단하고 등록을 해제합니다.
이미 실행 중인 관리 콜백이 반환할 때까지 모듈 JAR과 로더를 유지합니다. 메인 스레드에서
join/get으로 기다리거나, 시간 초과를 이유로 실행 중인 클래스로더를 강제 종료하지 않습니다.

콜백이 정리되면 `disable`을 시도하고, 이후 등록한 정리 작업을 역순으로 각각 한 번 시도합니다.
한 정리 작업의 예외는 나머지를 막지 않습니다. 실패들은 합쳐서 보고합니다.

```java
enable {
    // 제공자와 독립적인 파일·소켓 등의 정리. 제공자가 멈춰도 계속 시도합니다.
    ctx.onClose(() -> saveLocalState());

    // 해당 제공자가 살아 있을 때만 호출해야 하는 API 정리.
    ctx.onPluginClose("PlaceholderAPI", () -> expansion.unregister());
}
disable {
    // disable 자체는 제공자 종료 때문에 생략하지 않습니다.
    saveLocalState();
    if (ctx.isPluginAvailable("PlaceholderAPI")) {
        // 제공자가 살아 있을 때만 가능한 마지막 API 작업
    }
}
```

위 정리 예제의 `saveLocalState`와 `expansion`은 모듈 작성자가 정의합니다.
`onClose`/`onPluginClose` 등록은 prepare/enable 단계의 서버 스레드에서만 허용합니다.
`onPluginClose`는 선언된 해당 제공자의 원래 인스턴스가 살아 있는지를 각 정리 직전에 검사하고,
그 작업만 생략합니다. 중지한 제공자의 자원 정리는 제공자 자신의 종료 계약을 따라야 합니다.

**엔진 자체가 아직 실행 중인 콜백을 가진 상태로 종료되면** 서버 스레드에서 사용자 코드를
안전하게 실행할 수 없으므로 disable/등록 정리를 생략하고 경고합니다. 산출물은 콜백 반환
후에만 닫습니다. 프로세스 강제 종료나 임의 외부 스레드까지 안전한 저장을 보장하지 않습니다.
중요한 데이터는 운영 중에도 저장하세요.

외부 API가 따로 보관하는 콜백·스레드·future는 자동 추적하지 않습니다. 해당 API의 취소와
종료 대기 계약을 직접 지켜야 합니다. 외부 API 자체의 스레드 제한도 일반 Java 플러그인과
동일하게 적용됩니다. 동기 API를 임의로 비동기로 바꾸지 않습니다.

운영에서는 서버 재시작을 권장합니다. 모듈 load/unload/reload는 개발용입니다. 제공 플러그인
hot reload/re-enable은 지원하지 않으며, 같은 인스턴스를 다시 켜도 재시작 전에는 재사용을
거부합니다. 런타임 내부 의존성 그래프나 private 필드를 변경하지 않습니다.

검증 방법과 실제 측정은 [외부 API 검증](../verification-external/README.md)을 참고하세요.
