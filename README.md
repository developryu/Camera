# <center> **Android Camera Module**</center>

카메라2 API를 기반으로 구현된 Jetpack Compose용 카메라 모듈입니다. 간단한 설정으로 고급 카메라 기능을 구현할 수 있습니다.

## 특징
- Jetpack Compose 지원
- Camera2 API 기반 구현
- 실시간 이미지 프로세싱 지원 (QR 스캔 등)
- 다양한 카메라 설정 지원 (화각, 플래시, 비율 등)
- 전/후면 카메라 전환 기능

## 사용 방법

### 기본 설정
CameraScreen 컴포저블을 사용하여 카메라 프리뷰를 구현할 수 있습니다.

```kotlin
@Composable
fun YourScreen() {
    val viewModel: CameraViewModel = viewModel()
    
    CameraScreen(
        viewModel = viewModel // 필수 매개변수
    )
}
```

### ViewModel 기능

CameraViewModel에서 제공하는 주요 기능들:

- `takePicture()`: 사진 촬영
- `swapAngle()`: 카메라 화각 변경
- `changeFlash()`: 플래시 모드 변경
- `changeRatio()`: 카메라 비율 변경
- `swapDirection()`: 전/후면 카메라 전환

### 실시간 이미지 프로세싱

ViewModel 생성 시 실시간 이미지 처리를 위한 콜백을 설정할 수 있습니다:

```kotlin
val viewModel = CameraViewModel(
    onProcessImage = { imageData ->
        // 실시간 이미지 데이터 처리
        // 예: QR 코드 스캔
    }
)
```

## 설정 가능한 값

### 카메라 비율 (CameraRatio)
```kotlin
enum class CameraRatio {
    RATIO_1_1,    // 1:1 비율
    RATIO_4_3,    // 4:3 비율
    RATIO_16_9,   // 16:9 비율
    RATIO_FULL    // 전체 화면
}
```

### 카메라 방향 (CameraDirection)
```kotlin
enum class CameraDirection {
    FRONT,    // 전면 카메라
    BACK,     // 후면 카메라
    UNKNOWN   // 알 수 없음
}
```

### 카메라 화각 (CameraAngle)
```kotlin
enum class CameraAngle {
    WIDE,     // 광각
    NORMAL,   // 일반
    UNKNOWN   // 알 수 없음
}
```

### 플래시 모드 (CameraFlash)
```kotlin
enum class CameraFlash {
    AUTO,     // 자동
    ON,       // 켜짐
    OFF,      // 꺼짐
    TORCH,    // 토치
    RED_EYE   // 적목 현상 감소
}
```

### 화질 설정 (CameraQuality)
```kotlin
enum class CameraQuality {
    LOW,      // 저화질
    MEDIUM,   // 중화질
    HIGH      // 고화질
}
```

## 예제 코드

```kotlin
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val viewModel = viewModel<CameraViewModel>()
            
            CameraScreen(
                viewModel = viewModel
            )
            
            // 카메라 제어 버튼 예시
            Button(onClick = { viewModel.takePicture() }) {
                Text("사진 촬영")
            }
            
            Button(onClick = { viewModel.swapDirection() }) {
                Text("카메라 전환")
            }
        }
    }
}
```

