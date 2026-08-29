# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

MuhPackets is a Paper (Minecraft server) plugin that logs inbound packets per-player to files under
`plugins/MuhPackets/logs/<playerName>/<timestamp>.log`. It compiles against Paper's internal
`net.minecraft.*` server classes via `paperweight-userdev`, so it is tied to a specific Minecraft version
(the dev bundle pinned in `build.gradle.kts`). It targets Paper 1.21.4 through current 26.x from a
single jar - see "Mappings / version support" below, which is the least obvious part of this repo.

## Commands

```bash
./gradlew build            # compile + produce a Mojang-mapped plugin jar
./gradlew runServer        # launch a test Paper server with the plugin
```

The first build decompiles a Paper server via paperweight-userdev and needs roughly 4 GB of free RAM;
it is OOM-killed on small machines.

There are no tests. CI (`.github/workflows/build.yml`) only runs `./gradlew build`.

## Architecture

Everything hangs off the netty pipeline; there are no Bukkit events.

- `MuhPackets` (JavaPlugin) registers a Paper `ChannelInitializeListener` that inserts a
  `PacketLoggerHandler` before the `packet_handler` in every connection's pipeline. It also owns the
  list of `LoggingSession`s and an async repeating task (`doPoll`, every 20 ticks) that flushes them.
  Handlers cannot be removed from already-open connections, so they consult `isAccepting()` rather
  than assuming the plugin is alive; `onDisable` clears that flag first, then drains every session.
- `PacketLoggerHandler` (`ChannelDuplexHandler`) sees inbound packets only (`channelRead`). A session
  is created lazily when a `ServerboundHelloPacket` arrives — that is where the player name comes
  from, so packets before login are never attributed. That name is unauthenticated and client
  controlled; it must go through `MuhPackets#sanitiseSessionName` before touching the filesystem.
  Connection phase comes from `getPacketListener().protocol()`, which is null early on.
- `LoggingSession` is a producer/consumer buffer: netty threads `log()` into a bounded
  `ConcurrentLinkedDeque`, the async poll task drains it in `process()`. It peeks, writes, then
  removes, so a failed write does not discard records; overflow is counted and reported.
  `process()` returning false is what removes the session from `MuhPackets.sessions`.
- `LogRecord` captures a packet. Field values are read **on the netty thread at capture time**, not
  at flush time — a buffered packet may reference released netty buffers by the time it is written.
  Reflection metadata is cached per class in a `ClassValue`. `shouldLogField` is the denylist for
  fields that must not be dumped (`FriendlyByteBuf`, signature/chat-session types) — extend it there
  when a new packet type dumps garbage or huge buffers.
- `MuhPacketsConfig` is a typed snapshot of `config.yml`, re-read on `reloadConfig()`. Its fields are
  volatile because it is written from the main thread and read from netty threads.
  `NetworkInterceptor` is an empty leftover class.

## Log output format

`LogRecord#write` emits exactly one line per packet:

```
[timestamp] [protocol] [packet] key=value key=value
```

The invariant worth preserving is **one record, one line**: values are escaped (`\`, newline, tab,
control chars) and quoted when they contain spaces or quotes, and long values are truncated. Field
order is declaration order via `LinkedHashMap`, which keeps logs diffable between runs. Files open
with a `#`-prefixed header describing the format. Anything client-supplied that reaches a log —
notably the raw player name — must go through `LogRecord#escaped`, or a crafted name can forge lines.

## Mappings / version support

Paper has been **Mojang-mapped at runtime since 1.20.5**, and 26.x ships no reobfuscation mappings at
all. Consequently the old `reflection-remapper` / `ObfHelper` machinery is gone: packet classes and
fields are read directly by their Mojang names.

The build deliberately compiles against the **oldest** supported dev bundle (`1.21.4`), not the newest:

- Paper 26.x class files are Java 25 (major 69); compiling against them forces Java 25 bytecode that
  will not load on 1.21.x servers. Building against 1.21.4 emits Java 21 bytecode, which loads fine
  on the Java 25 runtime that 26.x requires.
- Every NMS symbol this plugin touches is unchanged across 1.21.4 -> 26.2, so one jar covers the range.

Before bumping the floor or adding NMS calls, re-verify the range rather than assuming. The cheap check
that does not need a full userdev build: grab Mojang-mapped server jars (a Paper paperclip run with
`-Dpaperclip.patchonly=true` produces `versions/<ver>/paper-<ver>.jar`), then

```sh
javap -cp <paper.jar> net.minecraft.network.PacketListener      # confirm signatures
jdeps --multi-release base -verbose:class -cp "<paper.jar>:<libraries>/*" build/classes/java/main
```

`jdeps` reporting no "not found" means every reference resolves on that version. Note `Connection`
has no `protocol` field any more (gone by 1.21.4) - use `getPacketListener().protocol()`.

## Style

`.editorconfig`: 2-space indent, UTF-8, final newline. Existing code uses `@DefaultQualifier(NonNull.class)`
on the plugin class and mixes JetBrains and Checker Framework nullness annotations.
