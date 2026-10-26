# Live Stream Flutter SDK

Flutter SDK for live streaming — go live, watch streams, chat, gifts, e-commerce, analytics, and recorded playback. Built with GetX.

**Package:** `appscrip_live_stream_component`  
**Platforms:** Android, iOS, Web

---

## Features

- Live streaming (host & viewer)
- Real-time chat, gifts, hearts
- Multi-live, co-publish, PK battles
- Product linking & cart hooks
- Stream analytics
- Recorded stream player
- Theme / dark mode via Flutter `ThemeData`
- Localization (`en`, `fr`, `pt`)

---

## Installation

```yaml
dependencies:
  appscrip_live_stream_component:
    git:
      url: https://github.com/isometrikai/live-stream-flutter.git
      ref: main
```

```bash
flutter pub get
```

Complete platform setup:

- [Android](./README_android.md)
- [iOS](./README_ios.md)
- [Web](./README_web.md)

---

## Quick start

### Option 1 — Plug-and-play (`IsmLiveApp`)

Handles init and shows the default stream listing:

```dart
import 'package:appscrip_live_stream_component/appscrip_live_stream_component.dart';

final navigatorKey = GlobalKey<NavigatorState>();

MaterialApp(
  navigatorKey: navigatorKey,
  home: IsmLiveApp(
    configuration: config, // IsmLiveConfigData
    navigatorKey: navigatorKey,
  ),
);
```

### Option 2 — Manual init

Use when you need custom screens or control over startup:

```dart
void main() async {
  WidgetsFlutterBinding.ensureInitialized();

  await IsmLiveApp.initialize(
    config,
    navigatorKey: navigatorKey,
  );

  runApp(MyApp());
}

// Then navigate to IsmLiveStreamListing() or your own UI
```

`IsmLiveApp.isInitialized` is `true` only when init succeeded and a valid user token is present.

---

## Configuration

### `IsmLiveConfigData`

```dart
final config = IsmLiveConfigData(
  projectConfig: IsmLiveProjectConfig(
    accountId: '...',
    appSecret: '...',
    userSecret: '...',
    keySetId: '...',
    licenseKey: '...',
    projectId: '...',
    deviceId: '...',
  ),
  userConfig: IsmLiveUserConfig(
    userToken: '...',
    userId: '...',
    firstName: '...',
    lastName: '...',
    userEmail: '...',
    userProfile: '',
  ),
  mqttConfig: const IsmLiveMqttConfig(
    hostName: 'your-mqtt-host',
    port: 1883,
  ),
);
```

`userToken` must be non-empty for `initialize()`.

### `configureInterface`

Call once at startup (before or after init) to customize UI and behavior:

```dart
IsmLiveApp.configureInterface(
  productionMode: true,
  fontFamily: 'YourFont',
  locale: const Locale('en'),

  goLiveScreenConfigure: const IsmLiveGoLiveScreenConfigure(
    isScheduleStreamFeatureEnabled: true,
    isProductStreamFeatureEnabled: false,
    // onGoLiveButtonTap: (context, isScheduled, details, data) async => true,
  ),

  streamScreenConfigure: IsmLiveStreamScreenConfigure(
    showStreamMemberCount: true,
    showGiftMessagesInChat: true,
    // chatMessageBuilder / cartBuilder / onStreamScrollCallback / ...
  ),

  sideIconsConfigure: IsmLiveSideIconsConfigure(
    controlOptionCallback: (context, option, streamId, isHost, isCopublishing) async {
      if (option == IsmLiveStreamOption.gift) {
        // custom handling
        return true; // handled — skip SDK default
      }
      return false;
    },
  ),

  ecomConfigure: IsmLiveEcomConfigure(
    // pinItemCallback / buyNowCallback
  ),

  streamRecordingPlayerConfig: IsmLiveStreamRecordingPlayerConfig(
    showChatReplay: true,
    // onLoaded / onControlOption / control builders
  ),
);
```

Customization is grouped by screen:

| Config | Purpose |
|--------|---------|
| `goLiveScreenConfigure` | Go Live features, header/button builders, go-live tap |
| `streamScreenConfigure` | Live UI — chat, header, cart, scroll, overlays |
| `sideIconsConfigure` | Right-rail controls & tap handling |
| `ecomConfigure` | Product pin / buy-now |
| `streamRecordingPlayerConfig` | Recorded playback UI & actions |
| `coinsPlansWalletScreenConfigure` | Coins / wallet screen |

---

## Theming

Wrap your app with `IsmLiveData` and provide light/dark themes via `IsmLiveDataExtension`:

```dart
GetMaterialApp(
  themeMode: ThemeMode.system,
  theme: ThemeData(
    brightness: Brightness.light,
    extensions: const [
      IsmLiveDataExtension(theme: IsmLiveThemeData(/* light */)),
    ],
  ),
  darkTheme: ThemeData(
    brightness: Brightness.dark,
    extensions: const [
      IsmLiveDataExtension(theme: IsmLiveThemeData(/* dark */)),
    ],
  ),
  builder: (context, child) => IsmLiveData(child: child!),
);
```

Access colors with `context.liveTheme`.

---

## Useful APIs

```dart
IsmLiveApp.isInitialized
IsmLiveApp.isMqttConnected
IsmLiveApp.reconnectMqtt()
IsmLiveApp.coinsBalance
IsmLiveApp.walletBalance
IsmLiveApp.refreshCoinsBalance()
IsmLiveApp.setLocale(Locale('fr')) // en | fr | pt
```

Widgets:

- `IsmLiveApp` — init + default listing
- `IsmLiveStreamListing` — stream list
- `IsmLiveStreamRecordingPlayerView` — recorded playback

See [Stream Recording Player Spec](./SDK_STREAM_RECORDING_PLAYER_SPEC.md) for recording details.

---

## Example

The `example/` app shows a full integration (auth, config, `configureInterface`, themes).

```bash
cd example && flutter run
```

---

## Troubleshooting

| Issue | Check |
|-------|--------|
| Init fails | Non-empty `userToken`; required project/user/MQTT fields |
| Stream won't start | Camera/mic permissions; network |
| Callbacks ignored | Call `configureInterface` before using features; return `true` only when you handled the action |
| Dark mode off | Provide both `theme` / `darkTheme` with `IsmLiveDataExtension`, and wrap with `IsmLiveData` |

---

## License

See [LICENSE](./LICENSE).
