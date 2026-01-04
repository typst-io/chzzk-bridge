## 문제

- oAuth 기반인 CHZZK API 를 직접 테스트할 수 없음.

## 해답

- CHZZK API 에 연결하는 로직을 인터페이스로 추상화, 테스트 환경에선 가짜 구현체를 DI.

## 결과

- CHZZK API 연결을 인터페이스로 분리했기 때문에 테스트 환경에서 가짜 치지직 연결 Gateway 를 DI 해서 테스트가 가능해짐.

## 배경

기존 코드는 chzzk4j 라이브러리에 강결합으로 짜여져있었음.

CHZZK API 는 기본적으로 oAuth 기반 API 임. 이로 인한 테스트 어려움이 있음.

oAuth 기반 API 는 네이버에 로그인되어있는 사용자가 브라우저로 열었을 때 토큰을 얻어올 수 있음.

브라우저를 여는 무거운 작업은 CI 환경에서 테스트를 돌리기 어려움이 있음. 얻어온 토큰을 저장한다 한들 그 토큰이 만료되면 테스트는 쉽게 깨짐.

이로 인해 CHZZK API 에 연결하는 부분을 인터페이스로 추상화.

```kotlin
interface ChzzkGateway {
    fun login(): ChzzkUserData
    fun connectSession() 
}

class Chzzk4jGateway : ChzzkGateway {
    // ...
}
```

## 예외사항

- ChzzkGateway 인터페이스가 Chzzk API 에 대한 작업을 올바르게 표현했는지에 대한 근거가 없음. 따라서 인터페이스 구조를 변경하면서 드는 리팩터링 비용 예상.

- 또한 가짜 구현체로 테스트케이스가 성공했어도 실제 e2e 환경에선 어떤 문제가 발생할지는 미지수, e2e 테스트케이스를 짠다면 CI 환경에서 어떻게 토큰을 넣어줄지가 문제. 리서치 필요