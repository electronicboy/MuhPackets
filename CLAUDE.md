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

`./gradlew test` runs the unit tests. They cover the pieces that hold no server state -
`RecordBudget`, `SessionRateLimiter` (driven by an injected clock, never the wall clock),
`LoggingSession` and `HandlerRegistry` (via netty's `EmbeddedChannel`) - which is
why those classes take a `Logger` and their limits rather than a `MuhPackets`. Anything needing a
live server is still verified by hand. CI (`.github/workflows/build.yml`) runs `./gradlew build`,
which includes `test`.

## Architecture

Everything hangs off the netty pipeline; there are no Bukkit events.

- `MuhPackets` (JavaPlugin) registers a Paper `ChannelInitializeListener` that inserts a
  `PacketLoggerHandler` before the `packet_handler` in every connection's pipeline. It also owns the
  list of `LoggingSession`s and an async repeating task (`doPoll`, every 20 ticks) that flushes them.
  `onDisable` clears `accepting`, removes every handler it installed (see `HandlerRegistry`), then
  drains every session. Handlers still consult `isAccepting()`, because a packet can be in flight
  while that is happening and removal can fail on an unhealthy channel.
- `SessionRateLimiter` bounds how fast sessions may be opened, which is the axis a login flood
  actually moves along - a session lasts as long as its connection, so capping how many exist at
  once caps concurrent players instead. Token bucket, so a restart's reconnect storm still gets
  logged while a sustained flood does not. `max-sessions` survives as an off-by-default ceiling.
- `HandlerRegistry` records the `ChannelHandlerContext` of each installed handler, captured in
  `handlerAdded` and dropped in `handlerRemoved` (which netty also fires when a channel closes).
  `register` doubles as the shutdown interlock: channels initialise on netty threads while
  `shutdown` runs on the main thread, so a connection accepted mid-sweep would otherwise install a
  handler the sweep has already passed. Once shut down it refuses, and the handler removes itself.
  Paper offers no way to enumerate open connections, so this self-kept list is the only handle on
  them. It exists because a handler left in a live pipeline holds a strong reference to the plugin
  instance, and through it the plugin classloader, so without removal every reload leaks a plugin
  generation per open connection. `Connection.channel` is deliberately not used for this: it is
  public on Paper 1.21.4 but private in 26.2, whereas `ChannelHandlerContext` is plain netty and
  needs no version check.
- `PacketLoggerHandler` (`ChannelDuplexHandler`) sees inbound packets only (`channelRead`). A session
  is created lazily when a `ServerboundHelloPacket` arrives — that is where the player name comes
  from, so packets before login are never attributed. That name is unauthenticated and client
  controlled; it must go through `LogDirectory#sanitiseName` before touching the filesystem.
  Connection phase comes from `getPacketListener().protocol()`, which is null early on.
- `LoggingSession` is a producer/consumer buffer: netty threads `log()` into a bounded
  `ConcurrentLinkedDeque`, the async poll task drains it in `process()`. It takes the batch, writes,
  then releases, so a failed write does not discard records; overflow is counted and reported both
  to the console and, as a `#`-prefixed marker line, into the log file itself, so a log is legible
  on its own. `process()` returning false is what removes the session from `MuhPackets.sessions`.
  It takes a `Logger` and its limits rather than the plugin, which is what makes it unit-testable.
- `RecordBudget` is the server-wide ceiling on buffered records, shared by every session. The
  per-session limit does not bound a login flood, which multiplies it by the connection count; this
  does. Budget is released only after a batch is genuinely written, so the restore-on-failure path
  cannot double-count, and `abandon()` hands back a dead session's share.
- `LogRecord` captures a packet. Field values are read **on the netty thread at capture time**, not
  at flush time — a buffered packet may reference released netty buffers by the time it is written.
  Reflection metadata is cached per class in a `ClassValue`. `shouldLogField` is the denylist for
  fields that must not be dumped (`FriendlyByteBuf`, signature/chat-session types) — extend it there
  when a new packet type dumps garbage or huge buffers.
- `LogDirectory` owns the files themselves: naming, opening, headers and expiry. Split out of the
  plugin class because none of it needs a server, and because `sanitiseName` is the only thing
  between an unauthenticated, client-supplied name and the filesystem - it is tested accordingly.
  Its root and logger arrive as suppliers, since neither exists while the plugin's fields are
  being initialised.
- `MuhPacketsConfig` is a typed snapshot of `config.yml`, re-read on `reloadConfig()`. Its fields are
  volatile because it is written from the main thread and read from netty threads.

## Tuning

The defaults in `config.yml` are sized against observed traffic rather than picked for roundness,
so change them from the same starting point:

- A casual player runs **200-300 packets per second**. The flush task drains everything once a
  second, so `max-buffered-records` is really the per-connection ceiling on records captured per
  second; 10000 leaves a normal connection ~30s of slack if a flush stalls.
- The aim under attack is **enough coverage to identify what is being targeted** - attacks usually
  lean on something specific that induces decode or handling work - not a complete recording. The
  drop marker accounts for whatever is lost past the limit.
- **Login floods are a different class of problem** and mostly not worth recording. Beyond roughly
  10 logins/second sustained there is nothing new to learn, so `max-sessions-per-second` caps it
  there while `max-session-burst` stays large enough that a restart's reconnect storm is logged
  normally.

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

`.editorconfig`: 2-space indent, UTF-8, final newline.

Nullness is JSpecify, and only JSpecify - it is the one scheme Paper ships across the supported range
(26.x dropped checker-qual, and the JetBrains annotations were never bundled). Each package carries
`@NullMarked` in its `package-info.java`, so types are non-null by default and only genuine exceptions
are annotated `@Nullable`. Do not reintroduce `org.jetbrains.annotations` or Checker Framework
annotations, and when adding a field that is null before some lifecycle point, annotate it rather
than relying on the package default being loose.
