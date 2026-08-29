# MuhPackets

A [Paper](https://papermc.io) plugin that dumps inbound packets to disk, one log file per player
session. It exists for debugging and protocol analysis — when you need to see exactly what a client
sent, in order, with field values.

This is a debugging tool, not something you want running on a busy production server: it reflects
over every packet it logs and writes them to disk continuously.

## What it logs

One line per packet, written to `plugins/MuhPackets/logs/<player>/<timestamp>.log`:

```
[2026-08-29T19:04:11.482] [PLAY] [game.ServerboundMovePlayerPacket$Pos] {x=124.5, y=64.0, z=-88.3, onGround=true}
```

Fields are read reflectively from the packet's whole class hierarchy. A few types are deliberately
skipped because they are noise or contain sensitive/unbounded data — raw `FriendlyByteBuf` payloads
and chat signature data (`MessageSignature`, `LastSeenMessages.Update`). See `LogRecord#shouldLogField`.

Only **inbound** (serverbound) packets are logged. A session starts when the login hello packet
arrives — that is where the player name comes from — so pre-login traffic is not captured.

## Configuration

`plugins/MuhPackets/config.yml`:

| Key | Default | Meaning |
| --- | --- | --- |
| `only-log-play` | `true` | Only log packets once the connection reaches the `PLAY` phase. |
| `skip-move-packets` | `true` | Drop movement packets, which otherwise dominate the log. |
| `ignored-packets` | `[]` | Simple class names to skip, e.g. `ServerboundKeepAlivePacket`. |
| `clear-old-files-days` | `-1` | Delete logs older than N days on startup. `-1` disables cleanup. |

`/muhpackets` (permission `muhpackets.muhpackets`, default OP) reloads the config. Changes apply to
running sessions.

## Building

```sh
./gradlew build          # produces a Mojang-mapped plugin jar in build/libs
./gradlew runServer      # launch a test server with the plugin installed
```

Requires a JDK 21+ toolchain. The build uses
[`paperweight-userdev`](https://github.com/PaperMC/paperweight) to compile against Paper's internal
server classes, so the first build downloads and decompiles a Paper server — expect it to take a
while and want a few GB of RAM.

## Versions

The plugin compiles against Paper internals (`net.minecraft.*`), so it is tied to Paper's server
internals rather than the Bukkit API. Since Paper 1.20.5 the server runs **Mojang-mapped at runtime**,
which means no reobfuscation step and no reflection tricks are needed to read packet fields — earlier
versions of this plugin carried a `reflection-remapper` dependency purely to work around that, and it
is now gone.

See `CLAUDE.md` for the internal architecture.
