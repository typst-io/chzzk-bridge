# chzzk-bridge

치지직 연동을 요구하는 멀티서버 환경에서 브릿지로 쓸 수 있는 고성능 스탠드얼론 프로그램입니다.


## 서버 구성

| 서버       | 포트    | 바인딩       | 용도                   |
|----------|-------|-----------|----------------------|
| OAuth 서버 | 39680 | 0.0.0.0   | 외부 CHZZK OAuth 콜백 수신 |
| API 서버   | 39681 | 127.0.0.1 | 내부 마인크래프트 서버 연동      |

---

## OAuth 서버 엔드포인트

### GET /oauth_callback

CHZZK OAuth 리다이렉트 핸들러. 사용자가 앱을 승인하면 네이버에서 호출됨.

**쿼리 파라미터**

| 이름    | 타입     | 필수 | 설명                |
|-------|--------|----|-------------------|
| code  | String | O  | CHZZK에서 발급한 인가 코드 |
| state | String | O  | CSRF 보호용 상태 토큰    |

**응답 코드**

| 코드                        | 설명                           |
|---------------------------|------------------------------|
| 200 OK                    | 토큰 생성 완료, 세션 연결됨             |
| 204 No Content            | 중복 세션 (이미 존재함)               |
| 400 Bad Request           | `code` 누락 또는 유효하지 않은 `state` |
| 406 Not Acceptable        | 해당 사용자의 토큰이 이미 존재함           |
| 500 Internal Server Error | 세션 생성 실패                     |

---

## API 서버 엔드포인트

### POST /api/v1/subscribe

사용자를 CHZZK 이벤트에 구독. 토큰이 없으면 OAuth 연동 URL 반환.

**쿼리 파라미터**

| 이름   | 타입   | 필수 | 설명               |
|------|------|----|------------------|
| uuid | UUID | O  | 마인크래프트 플레이어 UUID |

**응답 코드**

| 코드                        | 바디                         | 설명              |
|---------------------------|----------------------------|-----------------|
| 200 OK                    | `{}`                       | 세션 생성 성공        |
| 204 No Content            | `{}`                       | 이미 세션이 존재함      |
| 401 Unauthorized          | `ApiSubscribeResponseBody` | 토큰 없음, OAuth 필요 |
| 500 Internal Server Error | `{}`                       | 세션 획득 실패        |

**응답 바디 (401 Unauthorized)**

```json
{
    "state": "base64_encoded_state_token"
}
```

`state` 값을 사용해 CHZZK 연동 URL 구성:

```
https://chzzk.naver.com/account-interlock?clientId={CLIENT_ID}&state={state}&redirectUri={REDIRECT_URI}
```

---

### POST /api/v1/unsubscribe

사용자의 CHZZK 세션 제거.

**쿼리 파라미터**

| 이름   | 타입   | 필수 | 설명               |
|------|------|----|------------------|
| uuid | UUID | O  | 마인크래프트 플레이어 UUID |

**응답 코드**

| 코드             | 바디            | 설명             |
|----------------|---------------|----------------|
| 200 OK         | `OK!`         | 세션 제거 성공       |
| 204 No Content | `No session!` | 해당 사용자의 세션이 없음 |

---

### GET /api/v1/sse (SSE)

CHZZK 메시지(채팅, 도네이션) 스트리밍을 위한 Server-Sent Events.

**쿼리 파라미터**

| 이름     | 타입   | 필수 | 설명                 |
|--------|------|----|--------------------|
| uuid   | UUID | O  | 마인크래프트 플레이어 UUID   |
| fromId | Int  | X  | 시작 메시지 ID (기본값: 1) |

**SSE 이벤트 형식**

```
data: {"elements":[...],"error":null}
```

**응답 바디 스키마**

```json
{
    "elements": [
        {
            "id": 1,
            "channelId": "6e06f5e1907f17eff543abd06cb62891",
            "senderId": "viewer_channel_id",
            "senderName": "시청자닉네임",
            "message": "스트리머님 안녕하세요!",
            "messageTime": 1704067200000,
            "payAmount": 0
        }
    ],
    "error": null
}
```

**ApiFetchChzzkMessage 필드**

| 필드          | 타입     | 설명                          |
|-------------|--------|-----------------------------|
| id          | Int    | 메시지 ID (자동 증가)              |
| channelId   | String | 스트리머의 CHZZK 채널 ID (32자 hex) |
| senderId    | String | 발신자의 CHZZK 채널 ID            |
| senderName  | String | 발신자 닉네임                     |
| message     | String | 채팅 메시지 내용                   |
| messageTime | Long   | Unix 타임스탬프 (밀리초)            |
| payAmount   | Int    | 후원 금액 (일반 채팅은 0)            |

**에러 응답**

```json
{
    "elements": [],
    "error": "Requires uuid query parameter"
}
```

---

## 인증 흐름

```
+---------------+                            +----------------+
|  Minecraft    |  1. POST /subscribe        |  chzzk-bridge  |
|    Server     | -------------------------> |   API Server   |
|               | <------------------------- |                |
+---------------+    401 + state token       +----------------+
        |
        | 2. Show interlock URL to player
        v
+---------------+                            +----------------+
|    Player     |  3. User authorizes        |     CHZZK      |
|   (Browser)   | -------------------------> |     OAuth      |
+---------------+                            +----------------+
                                                     |
        4. Redirect with code + state                |
                                                     v
                                             +----------------+
                                             |  chzzk-bridge  |
                                             |  OAuth Server  |
                                             +----------------+
                                                     |
        5. Token saved, session created              |
                                                     v
+---------------+                            +----------------+
|  Minecraft    |  6. POST /subscribe        |  chzzk-bridge  |
|    Server     | -------------------------> |   API Server   |
|               | <------------------------- |                |
+---------------+    200 OK                  +----------------+
        |
        | 7. GET /fetch (SSE)
        v
+---------------+                            +----------------+
|  Minecraft    |  <-- Event Streaming --    |  chzzk-bridge  |
|    Server     |                            |   API Server   |
+---------------+                            +----------------+
```
