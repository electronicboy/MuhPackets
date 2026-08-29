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
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import pw.valaria.muhpackets.MuhPackets;
import pw.valaria.muhpackets.logger.LogRecord;
import pw.valaria.muhpackets.logger.LoggingSession;

public class PacketLoggerHandler extends ChannelDuplexHandler {

  private final MuhPackets muhPackets;
  private final @Nullable Connection connection;
  private LoggingSession loggingSession;

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
  public void channelRead(@NotNull ChannelHandlerContext ctx, @NotNull Object msg) throws Exception {
    // This handler stays in the pipeline of already-open connections after the plugin is disabled,
    // so it has to check rather than assume the plugin is still there.
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
    if (loggingSession != null) {
      loggingSession.close();
    }
    super.channelUnregistered(ctx);
  }

  private LogRecord createRecord(Object msg) {

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

    if (msg instanceof Packet<?>) {
      return new LogRecord(protocol, (Packet<?>) msg);
    }

    return null;
  }
}
