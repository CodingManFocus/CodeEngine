# 외부 Java API 검증 보고서

2026-09-28 실행. 기준 main: `3afbac085fa0fe1e4994be236b747a78c97abcdf`.
실행한 수정 소스의 정확한 SHA-256 목록은 [source-manifest.txt](results/final/source-manifest.txt),
JAR 해시·JDK·CPU·메모리 제한은 [environment.json](results/final/environment.json)에 있습니다.
최종 측정 후 현재 소스와 이 manifest의 일치를 확인했습니다.

## 구현 판단과 피드백 반영

1. Skript-reflect의 Java API 접근 목적에 맞춰 `requires plugin` + `use`를 추가했습니다.
   javac가 서명·오버로드를 해결하고 생성 코드는 일반 `invoke*` 명령을 실행합니다.
   매 호출 MethodHandle 검색·리플렉션·인수 변환 경로를 도입하지 않았습니다.
2. 실제 제공자의 클래스 로더를 재사용하고 JAR/클래스 소유권을 검증합니다. API를 복사하여
   static 상태와 타입이 갈라지는 방식을 피했습니다. 누락·중복·종료한 제공자는 명시적으로 거부합니다.
3. 이전 설계의 제공자 전체 생존 조건이 로컬 저장까지 생략하던 문제를 수정했습니다.
   `disable`/독립 `onClose`는 계속 시도하고 `onPluginClose`는 해당 제공자 정리만 조건부 실행합니다.
   정상 종료에서 실제 파일로 확인했습니다. 엔진 자체의 미완료 콜백 종료는 별도 경계입니다.
4. 명령 결과가 이전 호출 값으로 남아도 비교가 통과하던 검증 결함을 수정했습니다.
   각 경로에 다른 sentinel과 실행 카운터를 사용하며, 모든 측정 배치의 실행 횟수도 검사합니다.
5. 원본 자료가 없는 실행을 `{}`로 요약할 수 없도록 summary 검증과 atomic replacement를 추가했습니다.
   누락/중복 측정·실패 로그·완료 마커 없는 실행을 거부합니다. 원본 로그와 CSV를 이번 PR에 보존합니다.
6. 측정상 직접 호출 비용은 일반 Java에 근접했습니다. 명령 경로의 23~37 ns 수준 추적 비용을
   없애기 위해 실행 중 로더 보호를 제거하지 않았습니다. 컴파일 캐시나 강제 정리도 추가하지 않았습니다.

## 통과한 검증

| 검증 | 결과 |
| --- | --- |
| 전체 Gradle build 및 Java 테스트 | build 성공, 최종 JUnit 96개 통과 |
| React Studio 문법·스니펫 등 | 기존 9개 테스트에 requires/plugin 검사 포함, 통과 |
| Python 원본 증거 검증 | 7개 통과 |
| 기존 실제 Paper 회귀 | 391개 통과; 해당 기존 runner는 리매핑을 끄는 설정 사용 |
| 무료 PlaceholderAPI 최종 비교 | 새 JVM 3회, 각 1,883개 검증 통과; 기본 리매핑 활성화 |
| 별도 종료 시나리오 | 5개, 합계 27개 검증 통과 |

최종 비교에는 미설치 제공자·미선언 API 실패, 정확한 Class 동일성, 실제 PlaceholderExpansion
등록/호출/해제, 외부 이벤트 전달/해제, 실패한 enable 정리, 6회 반복 load/unload,
256개 입력의 API/명령 결과 동등성, 제공자 종료와 stale 재활성화 거부가 포함됩니다.
생성자·필드·오버로드·제네릭·checked exception, 중복 타입, 병렬 클래스 해석은 JUnit에서 검사했습니다.

종료 시나리오는 `reentrant`(enable에서 제공자 종료), `engine-stop`(enable에서 엔진 종료),
`engine-disable-hook`(disable에서 엔진 종료), `provider-command-stop`(명령에서 제공자 종료),
`normal-stop`입니다. 실행 중 익명 내부 클래스 접근, 콜백 반환 후 산출물 제거, 로컬 정리 생존을 확인합니다.

## 성능 결과

실제 무료 PlaceholderAPI **2.11.6**, Paper **1.21.11 build 132**, Corretto **21.0.12.1**.
각 JVM에서 warmup 160 ticks, 측정 240 ticks. native/module 순서를 고정 seed로 교차 무작위화합니다.
API 배치는 1,024회, 명령 배치는 128회입니다. 로깅과 파일 I/O는 측정 구간 밖입니다.
일반 Java 플러그인과 모듈이 동일 입력·동일 expansion에 독립적으로 `setPlaceholders`를 호출합니다.

단위는 **ns/op**, 차이는 동일 tick에서 대응하는 배치 평균의 `module − native` 중앙값입니다.
각 경로 중앙값의 단순 차이와 같지 않을 수 있습니다. 음수는 속도 우위를 입증하지 않습니다.

| 작업 | JVM | 일반 Java 중앙값 | Code Engine 중앙값 | 대응 차이 중앙값 | 할당 차이 중앙값 B/op |
| --- | --- | ---: | ---: | ---: | ---: |
| API: 단일 placeholder | fork-1 | 212.69 | 217.54 | +1.60 | 0.00 |
| API: 단일 placeholder | fork-2 | 222.10 | 222.57 | +0.54 | 0.00 |
| API: 단일 placeholder | fork-3 | 207.64 | 206.98 | -0.34 | 0.00 |
| API: 네 placeholder | fork-1 | 613.08 | 611.59 | -19.23 | 0.00 |
| API: 네 placeholder | fork-2 | 597.21 | 600.26 | +6.80 | 0.00 |
| API: 네 placeholder | fork-3 | 577.92 | 577.93 | +0.87 | 0.00 |
| 명령 전체 경로 | fork-1 | 1158.11 | 1153.69 | +26.80 | 0.00 |
| 명령 전체 경로 | fork-2 | 1102.87 | 1108.12 | +23.43 | 0.00 |
| 명령 전체 경로 | fork-3 | 1114.41 | 1128.77 | +36.38 | 0.00 |

직접 API 차이는 **−19.23~+6.80 ns/op**, 명령 전체 경로 차이는 **+23.43~+36.38 ns/op**입니다.
2,160개 측정 쌍 모두 추가 측정 할당 차이가 0 B였습니다. 이는 API 자체가 무할당이라는 뜻이 아닙니다.
실제 할당량은 단일 API 약 437 B/op, 네 placeholder 약 909 B/op, 명령 약 3,045 B/op입니다.

전체 compile/load는 **195.06~302.58 ms**입니다. 의존성 인덱싱, javac, 스레드 전환 및 활성화를
포함하며 일반 Java 플러그인의 사전 빌드 비용과 비교한 수치가 아닙니다. 반복 로드는 새 모듈 로더를
쓰지만 JVM/compiler/OS 캐시는 warm 상태입니다. 실행 시 API 호출 비용과 구분해야 합니다.

각 경로 p95는 [summary.json](results/final/summary.json)에 있습니다. 이는 배치 평균 p95이며,
개별 호출 지연이나 서버 tick p95가 아닙니다. 격리된 컨테이너·무접속 서버의 제한된 측정이며
모든 API, 다중 접속 TPS, 지연 상한 또는 성능 차이 0을 보장하지 않습니다.

## 원본 자료 및 제한

- [최종 원본 자료](results/final): JVM별 server.log, samples.csv, cold-loads.csv, checks.txt,
  manifest, 환경, JUnit XML, 빌드 로그, native/module 바이트코드.
- [기존 회귀](results/regression), [일반 종료](results/normal-stop),
  [enable 제공자 종료](results/reentrant), [enable 엔진 종료](results/engine-stop),
  [disable 엔진 종료](results/engine-disable-hook), [명령 제공자 종료](results/provider-command-stop).
- [재현 방법](README.md), [공개 API와 종료 계약](../docs/plugin-dependencies.md).

로그에는 격리 환경의 Mojang public key/Paper 버전 확인 DNS 실패가 있습니다. 서버는 loopback
오프라인 테스트 모드이며 해당 네트워크 조회는 테스트 API 경로가 아닙니다. 의도적인 실패 시나리오는
오류 로그를 남깁니다. 실제 Paper 이벤트/플러그인 lifecycle 예외는 runner에서 실패로 처리합니다.

Bukkit plugin.yml 제공자 JAR 경로를 검증했습니다. paper-plugin.yml, 별도 API 로더,
제공자 hot reload, 임의 외부 비동기 콜백의 자동 추적은 지원하지 않습니다. 엔진이 실행 중 콜백을
가진 상태로 종료되면 사용자 종료 훅 대신 콜백 반환 후 산출물만 정리합니다. 운영에서는 재시작을 권장합니다.
이번 변경의 브라우저 검증은 문법 단위 테스트이며 실제 브라우저 E2E는 새로 실행하지 않았습니다.
