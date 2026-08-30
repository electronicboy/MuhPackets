package pw.valaria.muhpackets.network;

import io.netty.channel.Channel;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import net.minecraft.network.Connection;
import net.minecraft.network.ConnectionProtocol;
import net.minecraft.network.PacketListener;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.network.protocol.login.ServerboundHelloPacket;
import org.jspecify.annotations.Nullable;
import pw.valaria.muhpackets.MuhPackets;
import pw.valaria.muhpackets.logger.LogRecord;
import pw.valaria.muhpackets.logger.LoggingSession;

public class PacketLoggerHandler extends ChannelDuplexHandler {

  private final MuhPackets muhPackets;
  private final @Nullable Connection connection;
  private @Nullable LoggingSession loggingSession;

  public PacketLoggerHandler(MuhPackets muhPackets, Channel channel) {
    this.muhPackets = muhPackets;

    final ChannelHandler packetHandler = channel.pipeline().get("packet_handler");
    if (packetHandler instanceof Connection conn) {
      this.connection = conn;
    } else {
      // Previously this logged and then carried on with a null connection, turning every
      // subsequent packet on this channel into an NPE.
      this.connection = null;
      muhPackets.getLogger().warning("No usable packet_handler on channel " + channel
        + "; packets on this connection will not be logged");
    }
  }

  @Override
  public void handlerAdded(ChannelHandlerContext ctx) throws Exception {
    // Recorded here rather than in the channel initialiser because this is the context we would
    // need to undo the installation, and it is the only handle on this pipeline we ever get.
    if (!muhPackets.handlers().register(ctx)) {
      // The plugin shut down while this connection was being set up, so onDisable's sweep has
      // already been and gone. Nothing else will ever take this handler out, and leaving it would
      // pin the old plugin's classloader for the life of the connection.
      ctx.pipeline().remove(this);
      return;
    }
    super.handlerAdded(ctx);
  }

  @Override
  public void handlerRemoved(ChannelHandlerContext ctx) throws Exception {
    muhPackets.handlers().unregister(ctx);
    // Also fires when the channel closes, so this is a strictly more reliable place to close the
    // session than channelUnregistered - which never runs at all if we are removed first.
    if (loggingSession != null) {
      loggingSession.close();
    }
    super.handlerRemoved(ctx);
  }

  @Override
  public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
    // onDisable takes these handlers back out of their pipelines, but a packet can already be in
    // flight when that happens, and removal can fail on an unhealthy channel. Cheap enough to check
    // rather than assume the plugin behind us is still alive.
    if (!muhPackets.isAccepting()) {
      super.channelRead(ctx, msg);
      return;
    }

    if (msg instanceof ServerboundHelloPacket serverboundHelloPacket && loggingSession == null) {
      this.loggingSession = muhPackets.createLoggingSession(serverboundHelloPacket.name());
    }

    if (loggingSession != null) {
      final LogRecord record = createRecord(msg);
      if (record != null) {
        loggingSession.log(record);
      }
    }

    super.channelRead(ctx, msg);
  }

  @Override
  public void channelUnregistered(ChannelHandlerContext ctx) throws Exception {
    // Kept alongside handlerRemoved; close() is idempotent and whichever fires first is fine.
    if (loggingSession != null) {
      loggingSession.close();
    }
    super.channelUnregistered(ctx);
  }

  private @Nullable LogRecord createRecord(Object msg) {

    if (msg instanceof ServerboundMovePlayerPacket && muhPackets.getMuhPacketsConfig().isSkipMovePackets()) {
      return null;
    }

    // Paper has been Mojang-mapped at runtime since 1.20.5, and Connection#protocol was removed
    // outright; the packet listener is the supported way to ask which phase we are in. It is null
    // very early in a connection's life, so the phase is simply unknown at that point.
    final PacketListener listener = connection == null ? null : connection.getPacketListener();
    final ConnectionProtocol protocol = listener == null ? null : listener.protocol();

    if (protocol != ConnectionProtocol.PLAY && muhPackets.getMuhPacketsConfig().isLogPlayOnly()) {
      return null;
    }

    if (msg instanceof Packet<?> packet) {
      return LogRecord.capture(protocol, packet, muhPackets.getMuhPacketsConfig().getIgnoredPackets());
    }

    return null;
  }
}
