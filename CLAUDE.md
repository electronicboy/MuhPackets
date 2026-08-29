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
  The single `/muhpackets` command just reloads config.
- `PacketLoggerHandler` (`ChannelDuplexHandler`) sees inbound packets only (`channelRead`). A session is
  created lazily when a `ServerboundHelloPacket` arrives — that is where the player name comes from, so
  packets before login are never attributed. `channelUnregistered` closes the session.
- `LoggingSession` is a producer/consumer buffer: netty threads `log()` into a
  `ConcurrentLinkedDeque`, the async poll task drains it to a `FileWriter` in `process()`.
  `process()` returning false is what removes the session from `MuhPackets.sessions`.
- `LogRecord` formats one line per packet. Packet class names are taken directly from the Mojang-mapped class name
  (no deobfuscation step is needed), and field values are dumped by reflecting over the packet's whole
  class hierarchy. `shouldLogField` is the denylist for fields that must not be dumped
  (`FriendlyByteBuf`, signature/chat-session types) — extend it there when a new packet type dumps
  garbage or huge buffers.
- `MuhPacketsConfig` is a typed snapshot of `config.yml`, re-read on `reloadConfig()`.
  `NetworkInterceptor` is an empty leftover class.

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
