package pw.valaria.muhpackets.logger;

import net.minecraft.network.ConnectionProtocol;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.LastSeenMessages;
import net.minecraft.network.chat.MessageSignature;
import net.minecraft.network.chat.RemoteChatSession;
import net.minecraft.network.protocol.Packet;
import net.minecraft.world.phys.HitResult;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.Writer;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * One captured packet, ready to be written out.
 *
 * <p>Field values are read when the record is created, on the netty thread that handled the packet,
 * not later when it is written. Packets are not guaranteed to still be intact by flush time.</p>
 */
public class LogRecord {
  private final static String PACKET_PACKAGE = "net.minecraft.network.protocol.";
  private final static DateTimeFormatter DEFAULT = DateTimeFormatter.ISO_LOCAL_DATE_TIME;

  /**
   * Reflection setup is expensive and this now runs on the netty thread, so the loggable fields of
   * each packet class are resolved once and reused.
   */
  private static final ClassValue<List<Field>> LOGGABLE_FIELDS = new ClassValue<>() {
    @Override
    protected List<Field> computeValue(Class<?> type) {
      final List<Field> fields = new ArrayList<>();
      for (Class<?> clazz = type; clazz != null; clazz = clazz.getSuperclass()) {
        for (final Field field : clazz.getDeclaredFields()) {
          if (!shouldLogField(field)) {
            continue;
          }
          try {
            field.setAccessible(true);
            fields.add(field);
          } catch (Throwable ignored) {
            // Inaccessible under the module system; nothing useful we can do with it.
          }
        }
      }
      return List.copyOf(fields);
    }
  };

  private final @Nullable ConnectionProtocol protocol;
  private final String packetName;
  private final Map<String, String> fields;
  private final LocalDateTime time = LocalDateTime.now();

  private LogRecord(@Nullable ConnectionProtocol protocol, String packetName, Map<String, String> fields) {
    this.protocol = protocol;
    this.packetName = packetName;
    this.fields = fields;
  }

  /**
   * Captures a packet, or returns null if it is configured to be ignored.
   */
  public static @Nullable LogRecord capture(@Nullable ConnectionProtocol protocol, Packet<?> msg,
                                            Set<String> ignoredPackets) {
    final String packetName = packetName(msg.getClass());
    if (isIgnored(packetName, ignoredPackets)) {
      return null;
    }
    return new LogRecord(protocol, packetName, captureFields(msg));
  }

  public void write(Writer writer) throws IOException {
    writer.write("[%s] [%s] [%s] %s\n".formatted(
      DEFAULT.format(this.time), protocol == null ? "UNKNOWN" : protocol, packetName, fields));
  }

  /**
   * Shortens a packet class name for display, trimming the common protocol package when present.
   *
   * <p>Blindly cutting a fixed prefix length assumed every packet lives under that package; Paper's
   * own packet types and anything else outside it produced a mangled name or an exception.</p>
   */
  static String packetName(Class<?> type) {
    final String name = type.getName();
    return name.startsWith(PACKET_PACKAGE) ? name.substring(PACKET_PACKAGE.length()) : name;
  }

  /**
   * Matches against the trailing class name, with and without any nested-class suffix, so both
   * {@code ServerboundMovePlayerPacket} and {@code ServerboundMovePlayerPacket$Pos} work.
   */
  static boolean isIgnored(String packetName, Set<String> ignoredPackets) {
    if (ignoredPackets.isEmpty()) {
      return false;
    }
    if (ignoredPackets.contains(packetName)) {
      return true;
    }
    final String simple = packetName.substring(packetName.lastIndexOf('.') + 1);
    if (ignoredPackets.contains(simple)) {
      return true;
    }
    final int nested = simple.indexOf('$');
    return nested > 0 && ignoredPackets.contains(simple.substring(0, nested));
  }

  private static Map<String, String> captureFields(Packet<?> msg) {
    final Map<String, String> out = new LinkedHashMap<>();
    for (final Field field : LOGGABLE_FIELDS.get(msg.getClass())) {
      try {
        out.put(field.getName(), parseValue(field.get(msg)));
      } catch (Throwable ignored) {
        // A single unreadable field should not cost us the rest of the packet.
      }
    }
    return out;
  }

  private static boolean shouldLogField(Field field) {
    if (Modifier.isStatic(field.getModifiers()) || field.isSynthetic()) {
      return false;
    }

    if (field.getType() == FriendlyByteBuf.class) {
      return false;
    }

    if (field.getType() == MessageSignature.class) {
      return false;
    }

    return field.getType() != LastSeenMessages.Update.class;
  }

  private static String parseValue(@Nullable Object object) {
    if (object == null) return "null";

    if (object instanceof HitResult hitResult) {
      return "%s{pos=%s,type=%s}".formatted(object.getClass().getSimpleName(), hitResult.getLocation(), hitResult.getType());
    } else if (object instanceof RemoteChatSession.Data data) {
      return "%s{expiresAt=%s}".formatted("RemoteChatSession.Data", DEFAULT.format(data.profilePublicKey().expiresAt()));
    }
    return object.toString();
  }
}
