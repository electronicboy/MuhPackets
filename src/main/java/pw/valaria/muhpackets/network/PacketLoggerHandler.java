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
import xyz.jpenilla.reflectionremapper.ReflectionRemapper;

import java.lang.reflect.Field;
import java.util.Collections;
import java.util.HashMap;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Supplier;

public class PacketLoggerHandler extends ChannelDuplexHandler {

  private final MuhPackets muhPackets;
  private final Connection connection;
  private LoggingSession loggingSession;

  private static Function<Connection, ConnectionProtocol> protocolSupplier;

  static {

    ReflectionRemapper reflectionRemapper = ReflectionRemapper.forReobfMappingsInPaperJar();

    try {
        Field protocol = Connection.class.getDeclaredField(reflectionRemapper.remapFieldName(Connection.class, "protocol"));
        protocol.setAccessible(true); // has some perf improvements
        protocolSupplier = (connection) -> {
          try {
            return (ConnectionProtocol) protocol.get(connection);
          } catch (IllegalAccessException e) {
            return null;
          }
        };
      } catch (NoSuchFieldException e) {
      }

    if (protocolSupplier == null) {
      protocolSupplier = (connection) -> connection.getPacketListener().protocol();
    }


  }

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

    ConnectionProtocol protocol = protocolSupplier.apply(connection);

    if (protocol != ConnectionProtocol.PLAY && muhPackets.getMuhPacketsConfig().isLogPlayOnly()) {
      return null;
    }

    if (msg instanceof Packet<?>) {
      return new LogRecord(protocol, (Packet<?>) msg);
    }

    return null;
  }
}
