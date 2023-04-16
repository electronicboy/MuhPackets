package pw.valaria.muhpackets.network;

import io.netty.channel.Channel;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import net.minecraft.network.Connection;
import net.minecraft.network.ConnectionProtocol;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.network.protocol.login.ServerboundHelloPacket;
import org.jetbrains.annotations.NotNull;
import pw.valaria.muhpackets.MuhPackets;
import pw.valaria.muhpackets.logger.LogRecord;
import pw.valaria.muhpackets.logger.LoggingSession;

import java.util.Collections;
import java.util.HashMap;
import java.util.Set;

public class PacketLoggerHandler extends ChannelDuplexHandler {

  private final MuhPackets muhPackets;
  private final Connection connection;
  private LoggingSession loggingSession;

  public PacketLoggerHandler(MuhPackets muhPackets, Channel channel) {
    this.muhPackets = muhPackets;

    final ChannelHandler packetHandler = channel.pipeline().get("packet_handler");
    if (packetHandler == null) {
      muhPackets.getLogger().info("Failed to get packet handler?!");
    }
    this.connection = (Connection) packetHandler;
  }

  @Override
  public void channelRead(@NotNull ChannelHandlerContext ctx, @NotNull Object msg) throws Exception {

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
    loggingSession.close();
    super.channelUnregistered(ctx);
  }

  private LogRecord createRecord(Object msg) {

    if (msg instanceof ServerboundMovePlayerPacket && muhPackets.getMuhPacketsConfig().isSkipMovePackets()) {
      return null;
    }

    if (connection.protocol != ConnectionProtocol.PLAY && muhPackets.getMuhPacketsConfig().isLogPlayOnly()) {
      return null;
    }
    final Set<String> ignoredPackets = muhPackets.getMuhPacketsConfig().getIgnoredPackets();
    if (!ignoredPackets.isEmpty() && ignoredPackets.contains(msg.getClass().getSimpleName())) {
      return null;
    }

    if (msg instanceof Packet<?>) {
      return new LogRecord(connection.protocol, (Packet<?>) msg);
    }

    return null;
  }
}
