# 제공자를 명시하는 use 문법 변경 검증

2026-09-28 실행. PR #4의 기존 구현에 사용자 피드백을 반영했습니다.

```java
module placeholders;
use me.clip.placeholderapi.PlaceholderAPI from "PlaceholderAPI";
```

## 변경과 검증 기준

- 타입 이름과 제공자를 함께 선택합니다. `from`의 제공자는 모듈 생존 의존성에도 자동 등록합니다.
  `requires plugin`은 생존·전이 의존성만 선언하며 타입을 노출하지 않습니다.
- 제공자 JAR 전체를 javac 클래스패스에 나열하면 같은 클래스명은 JAR 순서에 따라 선택됩니다.
  이를 방지하기 위해 선택한 API와 서명 의존 타입만 담은 컴파일 전용 JAR를 만듭니다.
  실행 모듈에는 포함하지 않고, 실행 시 원래 제공자 로더의 Class를 사용합니다.
- 선택 API의 public/protected 서명·상속·제네릭 전체에서 바이너리 이름별 Class 동일성을 검사합니다.
  충돌하면 컴파일 전에 실패합니다. 내부 메서드 본문에서만 쓰는 중복 라이브러리는 허용합니다.
- 같은 `shared.Api`를 가진 두 실제 fixture JAR를 만들었습니다. 먼저 나열된 제공자와 다른
  제공자를 선택해 그 제공자에만 존재하는 메서드를 컴파일하고 실행했습니다. 두 API의 내부
  구현은 각각 서로 다른 `shaded.Unused`를 호출합니다. 모듈의 API Class 동일성과 실행 결과를
  검사했고, 잘못된 제공자를 선택하면 javac가 실패하는 것도 확인했습니다.
- 공개 제네릭 반환값/배열 인자의 동일 이름·다른 Class 충돌, 중첩 타입과 제네릭 bounds,
  지정 제공자에 없는 타입, from 누락, requires만으로 타입 접근하는 경우를 검사했습니다.
- 메타데이터 조회와 컴파일이 API 정적 초기화를 실행하지 않는 것, 런타임 호출 시에 초기화되는 것,
  API 클래스가 생성 모듈 JAR에 복제되지 않는 것도 확인했습니다.

구조는 Parser/JavaEmitter, ApiTypeGraph/ClassOrigin, SelectedApiJar,
ModuleClassLoader로 책임을 나눴습니다. 변수·메서드 이름은 camelCase를 따릅니다.

## 반복 성능 측정

무료 PlaceholderAPI 2.11.6 / Paper 1.21.11 build 132(기본 리매핑) / Corretto 21.0.12.1.
일반 Java 플러그인과 모듈이 실제 동일 expansion의 `setPlaceholders`를 독립 호출합니다.
각 새 JVM에서 warmup 160 ticks, 측정 240 ticks, 순서를 교차 무작위화했습니다.
API 배치는 1,024회, 명령 배치는 128회입니다. 입력·결과·실행 횟수도 검사합니다.

단위는 ns/op. 대응 차이는 같은 tick의 배치 평균 `module − native`의 중앙값이며,
두 경로 중앙값의 단순 차이와 다를 수 있습니다. 음수는 속도 우위를 입증하지 않습니다.

| 작업 | JVM | 일반 Java 중앙값 | Code Engine 중앙값 | 대응 차이 중앙값 | 할당 차이 B/op 중앙값 |
| --- | --- | ---: | ---: | ---: | ---: |
| API: 단일 placeholder | fork-1 | 214.39 | 217.86 | -0.94 | 0.00 |
| API: 단일 placeholder | fork-2 | 226.69 | 227.82 | -0.22 | 0.00 |
| API: 단일 placeholder | fork-3 | 212.39 | 210.09 | -1.62 | 0.00 |
| API: 네 placeholder | fork-1 | 595.42 | 604.64 | -3.21 | 0.00 |
| API: 네 placeholder | fork-2 | 593.33 | 587.26 | -4.43 | 0.00 |
| API: 네 placeholder | fork-3 | 599.93 | 596.95 | -2.46 | 0.00 |
| 명령 전체 경로 | fork-1 | 1154.08 | 1138.55 | -29.97 | 0.00 |
| 명령 전체 경로 | fork-2 | 1112.23 | 1102.72 | +38.10 | 0.00 |
| 명령 전체 경로 | fork-3 | 1186.91 | 1208.07 | +40.57 | 0.00 |

직접 API 호출 차이는 −4.43~−0.22 ns/op, 명령 전체 경로는 −29.97~+40.57 ns/op였습니다.
2,160개 측정 쌍 모두 할당 차이가 0 B였습니다. API 자체의 할당은 존재합니다.
새 문법은 호출 경로에 탐색·리플렉션을 추가하지 않습니다. 양쪽 바이트코드의 실제
`PlaceholderAPI.setPlaceholders` 호출이 `invokestatic`인 것도 보존했습니다.

전체 compile/load는 **245.72~350.09 ms**였습니다. JAR 인덱싱·타입 서명 탐색·컴파일용 JAR
생성·javac·스레드 전환·활성화를 포함한 시간입니다. 반복 로드 시 새 모듈 로더를 사용하지만
JVM/compiler/OS 캐시는 warm 상태입니다. 이전 실행의 195.06~302.58 ms보다 범위가 높으며,
타입 선택 검증의 개별 비용을 분리한 측정은 아닙니다. 호출 경로의 병목을 줄이면서 명확한 타입
검사를 유지하기 위해 이 준비 비용을 수용했습니다. 결과를 이유로 생명주기 추적을 제거하거나
정확성을 희생하는 캐시·전역 클래스 선택을 추가하지 않았습니다.

명령 경로는 실행 추적 비용과 서버/JIT 변동을 포함합니다. 동일 컨테이너의 무접속 서버에서
특정 API 경로를 측정했으며, 모든 API나 실제 TPS·지연 상한을 보장하지 않습니다.
[summary.json](results/qualified-imports/summary.json)의 p95도 개별 호출이 아닌 배치 평균입니다.

## 검증 자료

- Java 108개, Studio 문법·스니펫 10개, Python 요약 검증 7개 통과:
  [빌드 로그·JUnit XML·바이트코드](results/qualified-validation).
- 실제 PlaceholderAPI 비교: 새 JVM 3회, 각 1,883개 검증 통과:
  [원본 CSV·로그·검증 목록](results/qualified-imports).
- 별도 종료 시나리오 5개, 합계 27개 검증 통과: [일반 종료](results/qualified-normal-stop),
  [enable 제공자 종료](results/qualified-reentrant), [enable 엔진 종료](results/qualified-engine-stop),
  [disable 엔진 종료](results/qualified-engine-disable-hook),
  [명령 제공자 종료](results/qualified-provider-command-stop).
- 기존 실제 Paper 회귀 392개 통과: [원본 결과](results/qualified-regression).
  기존 회귀 runner의 플러그인 리매핑 비활성화 설정을 유지했습니다.
- 실행 소스의 [SHA-256 manifest](results/qualified-imports/source-manifest.txt)와
  [JAR 해시·환경](results/qualified-imports/environment.json)을 보존했습니다.
  `gitBaseCommit`은 수정 전 기반 커밋이며 실행 소스는 manifest로 식별합니다.

이전 실행의 결과는 [최초 보고서](REPORT.md)에 역사적 자료로 보존했습니다.
로그의 예상된 실패 테스트 및 격리 환경의 Mojang/Paper 네트워크 조회 실패는 실제 검증 실패와
구분합니다. [재현 방법](README.md), [지원 계약](../docs/plugin-dependencies.md)을 참고하세요.
서명에 누락된 선택적 라이브러리가 있거나 별도 API 로더를 사용하는 경우에는 준비가 실패합니다.
`Object`/리플렉션으로만 전달되는 타입은 정적 서명 검사의 보장 범위 밖입니다.
