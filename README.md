# 멀티 세션 챗봇 구현 계획

로그인한 사용자마다 독립된 여러 대화 세션을 만들고, 각 세션의 문맥과 이력을 이어 가는 Spring AI 기반 챗봇을 구현한다. 백엔드는 Spring Boot 3.5와 Spring AI 1.0을 사용하며, 개발 환경은 Ollama, 운영 환경은 OpenAI `gpt-4o-mini`를 사용한다.

## 목표

- 한 사용자가 여러 대화를 생성하고 전환할 수 있다.
- 대화 문맥은 **사용자와 세션의 조합**으로 완전히 분리된다.
- 사용자는 자신의 세션과 메시지만 조회, 전송, 삭제할 수 있다.
- AI 응답은 SSE(Server-Sent Events)로 스트리밍한다.
- 사용자별 일일 사용량을 제한한다.
- 대화 세션, 대화 이력, AI 메모리를 Redis에 저장한다.

## 기술 기준과 환경 분리

| 구분 | 선택 |
| --- | --- |
| 백엔드 | Java 21, Spring Boot 3.5 |
| AI 연동 | Spring AI 1.0 |
| 개발 모델 | 로컬 Ollama 모델 |
| 운영 모델 | OpenAI `gpt-4o-mini` |
| 세션·이력·메모리·쿼터 | Redis |
| 인증·인가 | Spring Security |
| 응답 방식 | SSE 스트리밍 |

Spring AI BOM은 `1.0.x`로 고정하고, OpenAI와 Ollama 모델 starter를 함께 추가한다. 현재 프로젝트의 Spring AI BOM이 `1.1.8`이라면 이 요구사항에 맞춰 `1.0.x`로 맞추는 작업이 필요하다.

환경별 설정은 프로필로 분리한다.

```text
application.properties             공통: Redis, 보안, 메모리 정책
application-dev.properties         Ollama base URL, 모델명
application-prod.properties        OpenAI API 키, gpt-4o-mini 모델명
```

- `dev` 프로필에서는 Ollama만 활성화한다. API 키를 요구하지 않아야 한다.
- `prod` 프로필에서는 OpenAI만 활성화하고 API 키는 `OPENAI_API_KEY` 환경 변수로 주입한다.
- 두 모델용 설정과 `ChatClient` 빈이 동시에 활성화되지 않도록 프로필 또는 조건부 빈으로 분리한다.
- 프롬프트, 최대 출력 토큰, 온도 등 공통 정책은 가능하면 공통 설정으로 관리한다.

## 현재 코드 기준

현재 `ChatController`는 로그인한 사용자의 `username`을 받아 `ChatService`에 전달하며, `ChatService`는 `sessionId`를 Spring AI의 `CONVERSATION_ID`로 사용한다.

다만 `sessionId`만으로는 다음 문제가 남는다.

- 사용자 간 같은 세션 ID가 사용되면 대화 문맥이 섞일 수 있다.
- 세션의 소유자와 현재 로그인 사용자가 같은지 확인하지 않는다.
- `MessageWindowChatMemory`는 애플리케이션 메모리에만 있으므로 서버 재시작 시 문맥이 사라진다.
- `ChatSession`, `MessageRecord`, `SessionService`, `HistoryController`, `RedisConfig`가 아직 구현되지 않았다.

따라서 모든 세션·이력·AI 메모리는 `userId + sessionId`를 기준으로 관리한다.

## 설계 원칙

### 사용자 식별

- 인증 성공 후 `@AuthenticationPrincipal UserDetails`의 `username`을 `userId`로 사용한다.
- 요청 본문이나 URL에서 받은 `userId`는 신뢰하지 않는다.
- 세션 조회, 메시지 전송, 삭제 전에 항상 인증된 `userId`로 소유권을 확인한다.

### 대화 식별

클라이언트가 전달하는 `sessionId`는 UUID 형식으로 서버가 새 대화 생성 시 발급한다. AI 메모리에 전달하는 대화 ID는 다음처럼 사용자까지 포함한다.

```text
conversationId = user:{userId}:session:{sessionId}
```

이 값은 문맥 분리를 위한 식별자일 뿐이다. 접근 제어는 반드시 세션 데이터의 `ownerId == authenticatedUserId` 비교로 수행한다.

### 저장소 역할 분리

| 대상 | 권장 저장소 | 역할 |
| --- | --- | --- |
| 세션 메타데이터 | Redis | 소유자, 제목, 생성/수정 시각, 상태 |
| 메시지 이력 | Redis | 사용자에게 보여 줄 전체 대화 기록 |
| AI 단기 메모리 | Redis | 최근 N개 메시지를 AI 프롬프트에 제공 |
| 사용량 제한 | Redis | 사용자별 일일 카운터와 만료 시간 |

Redis는 Docker volume 등 영속 볼륨을 연결하고, Redis의 데이터 보존 정책(RDB/AOF)을 정한다. 대화 이력의 장기 보존, 검색·통계·감사가 추후 필요해지면 RDB 이전을 별도 단계로 검토한다.

## 도메인 모델

### `ChatSession`

세션 메타데이터를 표현한다.

| 필드 | 설명 |
| --- | --- |
| `id` | 서버가 생성한 UUID 세션 ID |
| `ownerId` | 세션 소유 사용자 ID |
| `title` | 대화 목록에 보여 줄 제목 |
| `createdAt` | 생성 시각 |
| `updatedAt` | 마지막 메시지 시각 |
| `status` | `ACTIVE`, `DELETED` 등 세션 상태 |

제목은 첫 사용자 메시지 앞부분으로 만들거나, 첫 응답 이후 AI 요약으로 갱신한다. 목록 조회에는 메시지 전문 대신 제목과 마지막 메시지 미리보기만 제공한다.

### `MessageRecord`

한 건의 대화 메시지를 표현한다.

| 필드 | 설명 |
| --- | --- |
| `id` | 메시지 ID |
| `sessionId` | 소속 세션 ID |
| `role` | `USER` 또는 `ASSISTANT` |
| `content` | 메시지 본문 |
| `sequence` | 세션 내 정렬 순서 |
| `createdAt` | 생성 시각 |

`userId`는 메시지마다 중복 저장하기보다 `ChatSession.ownerId`로 관리해도 된다. 다만 Redis 키와 조회 조건에는 사용자 범위를 포함한다.

## Redis 키 설계

```text
chat:session:{userId}:{sessionId}        세션 메타데이터
chat:sessions:{userId}                   사용자의 세션 목록(최근 수정 순서)
chat:history:{userId}:{sessionId}        전체 메시지 이력
chat:memory:{userId}:{sessionId}         AI에 전달할 최근 문맥
chat:quota:{userId}:{yyyy-MM-dd}         일일 사용량 카운터
```

- 키에 `userId`를 포함해 데이터 범위를 명확히 한다.
- `chat:sessions`는 정렬된 집합(ZSET)으로 두고 `updatedAt`을 score로 사용하면 최근 대화 목록을 쉽게 조회할 수 있다.
- `chat:memory`는 최근 20개처럼 크기를 제한하고 TTL을 설정한다.
- `chat:history`의 보존 기간은 요구사항에 맞게 정한다. 자동 만료가 필요하면 TTL을 명시한다.
- 운영 환경에서는 사용자 ID에 공백, 콜론 등 키 구분 문자가 들어올 수 있는지 정책을 정하거나 안전하게 인코딩한다.

## API 설계

`sessionId`를 요청 본문이 아니라 URL 경로로 받으면 리소스 관계가 더 명확하다.

| 메서드 | 경로 | 설명 |
| --- | --- | --- |
| `POST` | `/api/chat/sessions` | 새 세션 생성 |
| `GET` | `/api/chat/sessions` | 로그인 사용자의 세션 목록 조회 |
| `GET` | `/api/chat/sessions/{sessionId}` | 특정 세션의 메시지 이력 조회 |
| `DELETE` | `/api/chat/sessions/{sessionId}` | 특정 세션 삭제 |
| `POST` | `/api/chat/sessions/{sessionId}/stream` | 메시지 전송 및 SSE 응답 수신 |

스트림 요청 본문은 메시지만 받도록 단순화한다.

```json
{
  "message": "지난 대화 내용을 이어서 설명해줘"
}
```

SSE 이벤트는 다음처럼 구분한다.

```text
event: message    // AI 출력 조각
event: error      // 할당량 초과, AI 호출 실패 등
event: done       // 정상 완료
```

인증되지 않은 요청은 `401`, 타인 세션 접근은 `403`, 없는 세션은 `404`, 메시지 검증 실패는 `400`, 일일 한도 초과는 `429`로 응답한다.

## 컴포넌트별 구현 순서

### 1. 의존성과 설정

1. Spring AI BOM 버전을 `1.0.x`로 맞추고, OpenAI 및 Ollama 모델 starter를 추가한다.
2. Redis를 사용할 의존성을 추가하고 연결 정보를 `application.properties` 또는 환경 변수로 분리한다.
3. `RedisConfig`에 Redis 연결 및 필요한 직렬화 방식을 설정한다. 객체는 JSON으로 직렬화하고 날짜 타입의 역직렬화도 검증한다.
4. 개발 프로필은 Ollama 연결과 모델명을, 운영 프로필은 OpenAI `gpt-4o-mini` 및 API 키를 설정한다.
5. 개발용 인메모리 `MessageWindowChatMemory`를 Redis 기반 `ChatMemory` 구현으로 교체하거나, Redis에 저장하는 별도의 메모리 저장 어댑터를 구현한다.
6. AI 메모리는 전체 이력과 별개로 최근 메시지 수를 제한한다.

### 2. 인증과 인가

1. `SecurityConfig`에서 `/api/chat/**`를 인증 사용자만 접근하도록 설정한다.
2. 개발용 사용자 저장 방식 또는 실제 사용자 저장소를 정한다. 비밀번호는 BCrypt 해시로만 보관한다.
3. 컨트롤러에서는 현재 사용자 ID만 추출하고, 권한 판단은 서비스 계층에서 한 번 더 수행한다.

### 3. 세션 관리

1. `ChatSession`과 세션 생성·조회용 DTO를 정의한다.
2. `SessionService.create(userId)`에서 UUID를 생성하고 메타데이터와 사용자별 목록을 저장한다.
3. `SessionService.getOwnedSession(userId, sessionId)`를 구현한다.
4. 모든 세션 관련 작업은 이 메서드를 거치게 하여 소유권 검증을 일관되게 한다.
5. 삭제 시 세션, 목록 항목, 이력, AI 메모리를 함께 정리한다. 삭제 정책은 즉시 삭제 또는 `DELETED` 상태의 논리 삭제 중 하나로 정한다.

### 4. 메시지와 AI 스트리밍

1. 스트리밍 시작 전에 세션 소유권과 일일 한도를 검사한다.
2. 사용자 메시지를 이력에 먼저 저장하고 세션 `updatedAt`을 갱신한다.
3. `conversationId`에 `userId`와 `sessionId`를 모두 반영해 AI를 호출한다.
4. SSE chunk는 즉시 클라이언트로 전달하되, 서버에서는 응답 전문을 누적한다.
5. 스트림이 정상 완료된 경우에만 누적한 assistant 응답을 메시지 이력과 AI 메모리에 한 건으로 저장한다.
6. AI 호출 실패 또는 클라이언트 연결 해제 시 부분 응답을 저장할지 폐기할지 정책으로 정하고, `done` 이벤트는 정상 완료 때만 보낸다.

### 5. 이력 조회

1. `HistoryController`는 세션 목록과 메시지 이력 조회를 담당한다.
2. 이력 조회 전 `SessionService.getOwnedSession`으로 접근 권한을 확인한다.
3. 메시지가 길어질 것을 고려해 cursor 또는 page/size 기반 페이지네이션을 적용한다.
4. 목록은 최신 수정 순, 메시지는 `sequence` 또는 생성 시각 오름차순으로 반환한다.

### 6. 사용량 제한과 관측성

1. `QuotaService`는 `chat:quota:{userId}:{yyyy-MM-dd}`를 Redis의 원자 연산으로 증가시키고 제한을 초과하면 `429`를 반환한다. 동시 요청에서 한도를 넘기지 않도록 증가와 한도 판정을 분리하지 않는다.
2. 카운터 TTL은 다음 날 자정까지로 설정한다.
3. 메트릭 태그에는 사용자 ID 대신 사용자 수가 폭증하지 않는 범주형 값 또는 익명화된 값을 사용한다. 현재처럼 원문 `user_id`를 태그에 넣으면 메트릭 카디널리티가 커질 수 있다.
4. 로그에는 사용자 ID, 세션 ID, 처리 시간, 오류 유형만 남기고 메시지 원문 및 민감정보는 기록하지 않는다.

## 요청 처리 흐름

```text
클라이언트
  -> POST /api/chat/sessions/{sessionId}/stream
  -> 인증된 userId 확인
  -> SessionService: 세션 존재 여부 및 소유권 확인
  -> QuotaService: 일일 한도 확인 및 차감
  -> 사용자 메시지 이력 저장
  -> ChatService: userId + sessionId 기반 conversationId로 AI 호출
  -> SSE message 이벤트를 클라이언트에 전달
  -> 정상 완료: assistant 전문 저장, 세션 수정 시각 갱신, SSE done 전송
```

## 검증 시나리오

- 사용자 A가 세션 2개를 만들고 각각 다른 문맥이 유지되는지 확인한다.
- 사용자 A와 B가 서로 같은 형태의 세션 ID 요청을 보내도 문맥이 섞이지 않는지 확인한다.
- 사용자 B가 사용자 A의 세션 ID로 조회·전송·삭제할 때 `403`이 반환되는지 확인한다.
- 존재하지 않는 세션에 접근할 때 `404`가 반환되는지 확인한다.
- 서버 재시작 후 Redis 기반 세션, 이력, 메모리 보존 정책이 의도대로 동작하는지 확인한다.
- AI 응답이 여러 SSE `message` 이벤트로 오고 마지막에 `done` 이벤트가 한 번만 오는지 확인한다.
- AI 호출 중 오류가 나면 오류 이벤트가 전달되고 assistant 메시지가 잘못 저장되지 않는지 확인한다.
- 사용자별 일일 한도가 독립적으로 적용되고 자정 이후 카운터가 초기화되는지 확인한다.

## 구현 완료 기준

- 로그인 사용자만 챗봇 API를 이용할 수 있다.
- 새 세션 생성, 목록 조회, 이력 조회, 메시지 스트리밍, 세션 삭제가 가능하다.
- 모든 세션 접근에서 소유권 검증이 수행된다.
- AI 문맥은 사용자와 세션 조합별로 분리된다.
- 사용자/assistant 메시지 이력과 세션 수정 시각이 정상적으로 저장된다.
- 단위 테스트와 통합 테스트로 세션 격리, 권한, SSE 완료, 사용량 제한을 검증한다.
