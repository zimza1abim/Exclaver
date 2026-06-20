# Exclaver

한국어 SmartRoute 사용을 위해 [Exclave](https://github.com/ExclaveNetwork/Exclave)를 기반으로 수정한 Android 프록시 앱입니다.

[English README](README.en.md)

## 주요 기능

- 앱 이름: `Exclaver`
- 패키지 이름: `com.sampplekorea.exclaver`
- 한국어 UI 기본 지원
- Smart Route 관리 메뉴 제공
- WireGuard `.conf` 2개로 커스텀 라우팅 JSON 자동 생성
- GitHub Actions 기반 APK 빌드 및 릴리즈
- release APK는 GitHub Secrets에 저장된 고정 키로 서명

## 다운로드

최신 APK는 GitHub Releases에서 받을 수 있습니다.

https://github.com/sampple-korea/Exclaver/releases

일반적인 Android 기기는 `arm64-v8a` APK를 사용하면 됩니다.

## Smart Route란?

Smart Route는 기본 경로와 우회 경로를 나누는 개인용 라우팅 기능입니다.

- 기본 경로 conf: 평상시 사용할 WireGuard conf입니다. 보통 WARP를 넣습니다.
- 우회 경로 conf: 지정한 도메인에만 사용할 WireGuard conf입니다. 보통 Proton VPN을 넣습니다.
- 도메인 리스트: 이 목록에 있는 도메인만 우회 경로로 보냅니다.

동작 예시:

```text
기본 트래픽 -> WARP conf
지정 도메인 -> Proton conf
```

## Smart Route 사용법

1. Exclaver APK를 설치합니다.
2. 앱 왼쪽 메뉴를 엽니다.
3. `스마트 라우트`를 선택합니다.
4. 처음 설정할 때는 `경로 conf 설정 보기`에서 다음 값을 넣습니다.
   - 기본 경로 conf
   - 우회 경로 conf
5. `도메인 또는 URL` 입력란에 우회할 도메인을 넣고 `도메인 추가`를 누릅니다.
6. 필요한 경우 도메인을 검색하거나 체크해서 삭제합니다.
7. `스마트 라우트 저장/적용`을 누릅니다.
8. 저장된 Smart Route 프로필이 선택되고, 실행 시 라우팅이 적용됩니다.

이미 설정된 상태에서는 도메인 관리가 먼저 보입니다. conf 설정은 필요할 때만 `경로 conf 설정 보기`를 눌러 열면 됩니다.

## 도메인 관리

지원하는 입력 예시:

```text
example.com
domain:example.com
full:example.com
keyword:example
regexp:...
geosite:google
https://example.com/path
*.example.com
```

정규화 규칙:

- `example.com` -> `domain:example.com`
- `*.example.com` -> `domain:example.com`
- `https://example.com/path` -> `domain:example.com`
- `domain:example.com`, `full:example.com`, `keyword:example`, `regexp:...`, `geosite:google`은 그대로 사용
- 빈 줄은 무시
- 중복은 제거
- 일반 ASCII 도메인은 소문자로 변환

도메인 목록에서는 다음 작업을 할 수 있습니다.

- 도메인 추가
- 도메인 검색
- 보이는 항목 전체 선택
- 선택 해제
- 체크한 도메인 제외
- 도메인 길게 눌러 수정
- txt 파일에서 가져오기
- txt 파일로 내보내기
- 중복 제거/정규화
- 정렬
- 전체 지우기

## 라우팅 결과

예시 설정:

```text
기본 경로 conf = WARP.conf
우회 경로 conf = Proton.conf

도메인 리스트:
claude.ai
anthropic.com
reddit.com
redd.it
```

결과:

```text
claude.ai, anthropic.com, reddit.com, redd.it -> Proton
나머지 전체 트래픽 -> WARP
```

생성되는 커스텀 설정은 기존 Exclave custom config 프로필로 저장됩니다. 별도 프록시 코어를 만들지 않고, WireGuard outbound와 routing rule을 자동 생성합니다.

## WireGuard conf 지원 항목

`[Interface]`

- `PrivateKey`
- `Address`
- `DNS`
- `MTU`

`[Peer]`

- `PublicKey`
- `PresharedKey` 또는 `PreSharedKey`
- `AllowedIPs`
- `Endpoint`
- `PersistentKeepalive`
- `Reserved` 또는 `reserved`

`Reserved` 값은 WARP 호환을 위해 보존합니다.

## 빌드

이 저장소는 GitHub Actions로 APK를 빌드합니다.

수동 실행:

1. GitHub Actions로 이동합니다.
2. `Smart Route APK` workflow를 실행합니다.
3. tag 값을 넣으면 GitHub Release가 생성됩니다.

사용하는 signing secrets:

```text
ANDROID_KEYSTORE_BASE64
ANDROID_KEYSTORE_PASSWORD
ANDROID_KEY_ALIAS
ANDROID_KEY_PASSWORD
```

release APK는 secrets가 있을 때만 빌드됩니다. secrets가 없으면 debug APK만 artifact로 업로드되고 workflow 전체는 실패하지 않도록 구성되어 있습니다.

## 개발 정보

- JDK 21
- Go 1.26.4
- Go Mobile
- Android NDK r29
- Gradle wrapper
- 기본 릴리즈 브랜치: `smart-route-ko`

주요 빌드 작업:

```bash
./run lib core
./gradlew :app:downloadAssets
./gradlew :app:assembleOssDebug
./gradlew :app:assembleOssRelease
```

## 주의

- 실제 WARP/Proton private key를 이 저장소에 커밋하지 마세요.
- Smart Route에서 생성된 JSON을 공유할 때는 private key를 반드시 가리세요.
- 패키지 이름이 원본 Exclave와 다르므로 원본 앱과 별도 앱으로 설치됩니다.

## 라이선스

이 프로젝트는 원본 Exclave와 동일하게 GNU General Public License 계열 라이선스를 따릅니다. 자세한 내용은 [LICENSE](LICENSE)를 확인하세요.

## 출처

- [Exclave](https://github.com/ExclaveNetwork/Exclave)
- [SagerNet](https://github.com/SagerNet/SagerNet)
- [Shadowsocks Android](https://github.com/shadowsocks/shadowsocks-android)
