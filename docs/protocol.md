# LAN protocol

The player listens on TCP port **8765**: plain HTTP, or HTTPS when encryption is on (a key generated in the Android keystore, or an imported `.p12`). The phone pairs over plain HTTP first and, if the player answers in TLS, reconnects over HTTPS and pins that certificate's SHA-256; later it accepts only the pinned certificate, or a new one the system trusts for the paired address.
Every request carries the 4-digit pairing code in an `X-Token` header; a wrong code gets `401`, and five wrong codes from one address lock it out for a minute (`429`).
All responses are JSON except `/art`.

`app` is one of `com.apple.android.music`, `com.tencent.qqmusic`, `com.netease.cloudmusic`.
Without it the player uses whichever supported app is playing.

| Request | Result |
|---|---|
| `GET /state?app=` | `alive`, `app`, `state` (Android `PlaybackState` code, 3 = playing), `pos`, `duration` (ms), `title`, `artist`, `album`, `queue[]` (`id`, `title`, `artist`), `activeId`, `vol`, `volMax` |
| `GET /art?app=` | album art as PNG, `404` if none |
| `GET /cmd?c=<command>&v=<value>&app=` | `{"ok":true}` or `{"error":"…"}` |
| `GET /src` | SRC report for a USB DAC: `verdict`, `device`, `track` (`app`, `rate`, `bitPerfect`), `outRate`, `outFormat`, `why[]` |

Commands:

| `c` | `v` | Effect |
|---|---|---|
| `mediaid` | song ID | play that song. Apple Music: `playFromMediaId`; QQ Music / NetEase: the app's own song link (`qqmusic://…playSonglist`, `orpheus://song/<id>`) |
| `play`, `pause`, `next`, `prev` | | transport controls |
| `seek` | ms | seek |
| `skipq` | queue item id | jump to a queue item |
| `vol` | 0…`volMax` | media volume; `volmax` sets the maximum |
| `open` | | mirroring started: start the app and leave standby |
| `close` | | mirroring stopped: pause and stop the music apps Music Relay started, go to standby |

The phone side (`RedirectService`) watches its own music app's MediaSession and sends
`mediaid` when the song changes, `play` / `pause` on state changes, and `seek` when the
position jumps or drifts more than 2.5 s from the player's.
