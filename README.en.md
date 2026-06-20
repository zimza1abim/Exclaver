# Exclaver

Exclaver is an Android proxy app based on [Exclave](https://github.com/ExclaveNetwork/Exclave), customized for Korean Smart Route usage.

[한국어 README](README.md)

## Highlights

- App name: `Exclaver`
- Package name: `com.sampplekorea.exclaver`
- Korean-first UI
- Dedicated Smart Route management menu
- Generates an Exclave custom config from two WireGuard `.conf` files
- GitHub Actions APK builds and releases
- Release APKs are signed with a stable key stored in GitHub Secrets

## Download

Download the latest APK from GitHub Releases:

https://github.com/sampple-korea/Exclaver/releases

Most Android phones should use the `arm64-v8a` APK.

## What Is Smart Route?

Smart Route splits traffic between a default WireGuard route and a bypass WireGuard route.

- Default route conf: the WireGuard conf used for normal traffic. Usually WARP.
- Bypass route conf: the WireGuard conf used only for selected domains. Usually Proton VPN.
- Domain list: only domains in this list use the bypass route.

Example behavior:

```text
Default traffic -> WARP conf
Selected domains -> Proton conf
```

## Smart Route Usage

1. Install the Exclaver APK.
2. Open the left navigation drawer.
3. Select `Smart Route`.
4. On first setup, open `Show route conf settings` and add:
   - Default route conf
   - Bypass route conf
5. Enter a domain or URL and tap `Add domain`.
6. Search, select, edit, or remove domains as needed.
7. Tap `Save / apply Smart Route`.
8. The generated Smart Route profile is selected and used when the app runs.

After Smart Route is configured, the domain manager is shown first. The route conf settings stay collapsed until you open them.

## Proxy Port Sync

Smart Route does not store separate proxy ports. It uses the normal SOCKS/HTTP inbound settings from the app settings.

- SOCKS port: `Settings > SOCKS5 proxy port`
- HTTP port: `Settings > HTTP proxy port`
- SOCKS/HTTP enabled state also follows app settings.
- If you change proxy ports in settings, Smart Route uses the current ports when it runs.
- The Smart Route screen shows the currently active SOCKS/HTTP addresses.

For example, if the HTTP proxy port is `2081`, use `127.0.0.1:2081` in apps such as AdGuard. If `Allow access from LAN` is enabled, the screen shows `0.0.0.0:port`.

## Domain Management

Supported input examples:

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

Normalization rules:

- `example.com` -> `domain:example.com`
- `*.example.com` -> `domain:example.com`
- `https://example.com/path` -> `domain:example.com`
- Existing `domain:`, `full:`, `keyword:`, `regexp:`, and `geosite:` rules are preserved.
- Blank lines are ignored.
- Duplicates are removed.
- ASCII domains are lowercased.

Available domain actions:

- Add domain
- Search domains
- Select shown domains
- Clear selection
- Remove checked domains
- Long press a domain to edit it
- Import from txt
- Export to txt
- Normalize / remove duplicates
- Sort
- Clear all

## Routing Result

Example setup:

```text
Default route conf = WARP.conf
Bypass route conf = Proton.conf

Domain list:
claude.ai
anthropic.com
reddit.com
redd.it
```

Result:

```text
claude.ai, anthropic.com, reddit.com, redd.it -> Proton
All other traffic -> WARP
```

The generated configuration is saved as a normal Exclave custom config profile. Exclaver does not add a new proxy core; it generates WireGuard outbounds and routing rules for the existing core.

## Supported WireGuard Conf Fields

`[Interface]`

- `PrivateKey`
- `Address`
- `DNS`
- `MTU`

`[Peer]`

- `PublicKey`
- `PresharedKey` or `PreSharedKey`
- `AllowedIPs`
- `Endpoint`
- `PersistentKeepalive`
- `Reserved` or `reserved`

`Reserved` is preserved for WARP compatibility.

## Build

This repository builds APKs with GitHub Actions.

Manual release flow:

1. Open GitHub Actions.
2. Run the `Smart Route APK` workflow.
3. Enter a tag to create a GitHub Release.

Signing secrets:

```text
ANDROID_KEYSTORE_BASE64
ANDROID_KEYSTORE_PASSWORD
ANDROID_KEY_ALIAS
ANDROID_KEY_PASSWORD
```

Release APKs are built only when signing secrets are present. If secrets are missing, the workflow still uploads debug APK artifacts without failing the whole run.

## Development

- JDK 21
- Go 1.26.4
- Go Mobile
- Android NDK r29
- Gradle wrapper
- Main release branch: `smart-route-ko`

Main build tasks:

```bash
./run lib core
./gradlew :app:downloadAssets
./gradlew :app:assembleOssDebug
./gradlew :app:assembleOssRelease
```

## Notes

- Do not commit real WARP or Proton private keys.
- Always mask private keys before sharing generated JSON.
- The package name differs from upstream Exclave, so Exclaver installs as a separate app.

## License

This project follows the same GNU General Public License family license as upstream Exclave. See [LICENSE](LICENSE).

## Credits

- [Exclave](https://github.com/ExclaveNetwork/Exclave)
- [SagerNet](https://github.com/SagerNet/SagerNet)
- [Shadowsocks Android](https://github.com/shadowsocks/shadowsocks-android)
