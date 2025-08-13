# 🚌 Smart Bus Detection App for Visually Impaired
> 시각장애인을 위한 스마트 버스 감지 애플리케이션
>
> 음성 인식과 AI 객체 감지로 안전한 대중교통 이용을 돕습니다! 🎤📱🔍

## 📋 프로젝트 개요
BUSZ는 시각장애인이 대중교통을 독립적이고 안전하게 이용할 수 있도록 지원하는 애플리케이션입니다.
음성 인식을 통한 버스 번호 입력, 실시간 버스 도착 정보 음성 안내, AI 기반 버스 객체 감지 등
시각장애인이 필요로 하는 모든 기능을 하나의 앱에서 제공하고자 합니다.

## ✨ 주요 기능

### 🎤 음성 인식 기반 버스 조회
- **음성 버스 번호 입력**: 원하는 버스 번호를 음성으로 간편하게 입력
- **실시간 도착 정보**: WebSocket 통신으로 정확한 버스 도착 시간 제공
- **스마트 음성 안내**: 도착 시간에 따른 차별화된 안내 시스템
- **자동 재시도**: 버스 정보가 없을 경우 자동으로 재입력 유도

### 📷 AI 기반 실시간 객체 감지
- **CameraX 실시간 스트리밍**: 고성능 카메라 API로 안정적인 영상 처리
- **ML Kit 객체 감지**: Google ML Kit을 활용한 정확한 버스 감지
- **1.5초 간격 빠른 감지**: 시각장애인을 위한 최적화된 빠른 반응 속도
- **백그라운드 감지**: 사용자가 다른 작업 중에도 지속적인 버스 감지

### 🌐 실시간 버스 정보 연동
- **WebSocket 실시간 통신**: 30초 주기로 최신 버스 위치 정보 업데이트
- **GPS 기반 위치 서비스**: 현재 위치에서 정확한 버스 정보 제공
- **서버 연동**: 신뢰할 수 있는 대중교통 API와 연결
- **오프라인 대응**: 연결 오류 시 적절한 안내 및 재연결 시도

### 🔊 접근성 중심 음성 인터페이스
- **완전 음성 기반 UI**: 화면을 보지 않아도 모든 기능 이용 가능
- **TTS(Text-to-Speech)**: 명확하고 자연스러운 음성 안내
- **STT(Speech-to-Text)**: 정확한 음성 인식으로 편리한 입력
- **지능적 타이밍 제어**: 5분 이하는 30초마다, 5분 초과는 2분마다 안내

## 🚀 Tech Stack

### Frontend (Android)
- **Kotlin** + **Jetpack Compose**
- **CameraX** (카메라 기능)
- **ML Kit** (객체 감지)
- **Android Speech API** (TTS/STT)
- **Socket.IO Client** (실시간 통신)

### Real-time Communication
- **WebSocket** (실시간 버스 정보)
- **JSON** (데이터 교환 형식)
- **Socket.IO** (안정적인 실시간 통신)

### Location & Permissions
- **GPS/Network Location**
- **Camera Permission**
- **Microphone Permission**
- **Location Permission**

### 필수 요구사항
```
Android Studio Arctic Fox 이상
Kotlin 1.9.0+
Minimum SDK: API 24 (Android 7.0)
Target SDK: API 34 (Android 14)
```

## 📁 프로젝트 구조
```
📁 com/example/cv2_project1/
├── 📱 ui/
│   ├── 🎨 theme/
│   │   └── MainScreen.kt
│   └── 🖼️ components/          # UI 컴포넌트
├── 🎤 managers/
│   ├── SpeechManager.kt        # 음성 인식 관리
│   ├── CameraManager.kt        # 카메라 및 객체 감지
│   ├── LocationManager.kt      # GPS 위치 관리
│   └── WebSocketManager.kt     # 실시간 통신 관리
├── 🔍 detection/
│   └── ObjectDetectionManager.kt  # ML Kit 객체 감지
├── 📊 models/                  # 데이터 모델
└── 🛠️ utils/                  # 유틸리티 클래스
```

## 📱 주요 화면 및 기능

### 1. 메인 화면 (MainActivity.kt)
```
🎯 통합 제어 센터
- 모든 매니저 클래스 초기화 및 관리
- 권한 요청 및 상태 관리
- 생명주기에 따른 리소스 최적화
- 음성 인식 플로우 제어
```

### 2. 음성 인식 시스템 (SpeechManager.kt)
```
🎤 음성 입력 처리
- Google Speech-to-Text API 연동
- 버스 번호 패턴 인식 및 추출
- 노이즈 필터링 및 정확도 향상
- 재시도 로직 및 에러 처리
```

### 3. 실시간 객체 감지 (CameraManager.kt + ObjectDetectionManager.kt)
```
📷 AI 기반 버스 감지
- CameraX로 실시간 카메라 스트리밍
- ML Kit Object Detection API
- 1.5초 간격 최적화된 감지 주기
- 메모리 효율적인 이미지 처리
```

### 4. 실시간 버스 정보 (WebSocketManager.kt)
```
🌐 서버 통신 및 데이터 관리
- Socket.IO 기반 안정적인 연결
- 30초 주기 자동 버스 정보 업데이트
- JSON 데이터 파싱 및 검증
- 연결 상태 모니터링 및 재연결
```

### 5. 위치 서비스 (LocationManager.kt)
```
📍 GPS 기반 위치 추적
- 정확한 현재 위치 확인
- 배터리 최적화된 위치 업데이트
- 권한 관리 및 설정 유도
- 위치 기반 버스 정류장 정보
```

## 🔄 앱 플로우

```
📱 앱 실행
    ↓
🔒 권한 요청 (카메라, 마이크, 위치)
    ↓
🌐 서버 연결 (WebSocket)
    ↓
🎤 음성 인식 시작 (3초 후 자동)
    ↓
🚌 버스 번호 추출 및 서버 전송
    ↓
⏱️ 실시간 버스 도착 정보 수신
    ↓
┌─────────────────┬─────────────────┐
│   ✅ 버스 발견    │   ❌ 버스 없음    │
├─────────────────┼─────────────────┤
│ 🔊 도착시간 안내  │ 🔄 재시도 유도   │
│ 📷 카메라 시작   │ 🎤 재음성인식    │
│ 🔍 객체 감지    │                │
└─────────────────┴─────────────────┘
```

## 🛠️ 설치 및 실행

### 필수 요구사항
- Android Studio Arctic Fox 이상
- Kotlin 1.9.0+
- Minimum SDK: API 24 (Android 7.0)
- Target SDK: API 34 (Android 14)
- 실제 Android 기기 (카메라, 마이크 필요)

### 설치 방법

1. **저장소 클론**
```bash
git clone https://github.com/yourusername/Smart-Bus-Detection-App.git
cd Smart-Bus-Detection-App
```

2. **Android Studio에서 프로젝트 열기**
- Android Studio 실행
- "Open an existing project" 선택
- 클론한 폴더 선택

3. **의존성 설치**
```bash
./gradlew build
```

4. **앱 실행**
- Android 기기 연결 (에뮬레이터는 카메라 제한으로 권장하지 않음)
- Run 버튼 클릭 또는 `Ctrl + R`

### 🔧 설정

#### 서버 URL 변경
`WebSocketManager.kt`에서 서버 URL을 수정하세요:
```kotlin
private val serverUrl = "https://your-server-url.ngrok-free.app"
```

#### 필수 권한
앱에서 다음 권한들이 필요합니다:
- 📷 **카메라 권한**: 실시간 객체 감지
- 🎤 **마이크 권한**: 음성 인식
- 📍 **위치 권한**: GPS 기반 버스 정보
- 🔊 **오디오 설정 권한**: TTS 음성 출력

## 🚀 주요 기능 상세

### ✅ 현재 구현된 기능

| 기능 | 설명 | 상태 |
|------|------|------|
| 🎤 음성 인식 | Google STT API 기반 버스 번호 인식 | ✅ 완료 |
| 🔊 음성 출력 | TTS 기반 실시간 도착 정보 안내 | ✅ 완료 |
| 📷 카메라 시스템 | CameraX 기반 실시간 스트리밍 | ✅ 완료 |
| 🔍 객체 감지 | ML Kit을 활용한 버스 감지 | ✅ 완료 |
| 🌐 실시간 통신 | Socket.IO 기반 서버 연동 | ✅ 완료 |
| 📍 위치 서비스 | GPS 기반 현재 위치 확인 | ✅ 완료 |
| ⏱️ 스마트 타이밍 | 도착 시간별 차별화된 안내 주기 | ✅ 완료 |
| 🔄 자동 재시도 | 버스 정보 없을 시 재입력 유도 | ✅ 완료 |

### 🚧 향후 개발 예정 기능

- 🗺️ **정류장 정보 연동**: 주변 버스 정류장 자동 감지
- 📊 **사용 통계**: 개인별 이용 패턴 분석 및 최적화
- 🔔 **푸시 알림**: 버스 도착 예정 시간 사전 알림
- 🌍 **다국어 지원**: 영어, 중국어 등 다양한 언어 지원
- ♿ **접근성 향상**: 시각장애 등급별 맞춤 설정
- 🎯 **OCR 버스 번호 인식**: 카메라로 버스 번호판 직접 읽기

## 🛠️ 기술적 특징

### 성능 최적화
- **메모리 효율성**: 카메라 스트림 최적화로 메모리 사용량 최소화
- **배터리 최적화**: GPS 사용 후 자동 중단으로 배터리 절약
- **네트워크 효율성**: WebSocket 연결 상태 지속적 모니터링

### 접근성 중심 설계
- **완전 음성 인터페이스**: 화면 없이도 모든 기능 이용 가능
- **직관적 음성 안내**: 명확하고 이해하기 쉬운 안내 메시지
- **에러 처리**: 사용자 친화적인 오류 상황 안내

### 안정성 및 신뢰성
- **코루틴 기반 비동기 처리**: UI 응답성 보장
- **예외 처리**: 모든 시나리오에 대한 철저한 에러 핸들링
- **생명주기 관리**: 앱 상태에 따른 적절한 리소스 관리

## 📖 사용법

### 기본 사용 흐름
1. **앱 실행**: 자동으로 권한 요청 및 서버 연결
2. **음성 입력**: "141번" 또는 "버스 141번" 등으로 말하기
3. **정보 확인**: 실시간 도착 시간 음성으로 확인
4. **버스 감지**: 카메라를 통해 접근하는 버스 자동 감지
5. **승차 준비**: 도착 알림에 따라 승차 준비

### 음성 명령 예시
```
✅ 인식 가능한 패턴:
- "141번"
- "버스 141번"  
- "141번 버스"
- "일사일번"

❌ 주의사항:
- 안드로이드 앱과 가까운 위치에서 말하기
- 명확한 발음으로 말하기
- 주변 소음 최소화
```


## 📧 문의 및 지원

- **이메일**: jamong909@gmail.com
- **이슈 신고**: [GitHub Issues](https://github.com/yourusername/Smart-Bus-Detection-App/issues)
- **기능 제안**: [GitHub Discussions](https://github.com/yourusername/Smart-Bus-Detection-App/discussions)

---

## 🙏 특별 감사

이 프로젝트는 시각장애인의 이동권 향상을 위해 개발되었으며, 관련 단체 및 당사자들의 소중한 피드백을 바탕으로 지속적으로 개선되고 있습니다.

**함께 만들어가는 더 나은 교통 환경** 🚌♿🤝
