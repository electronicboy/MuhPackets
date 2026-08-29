package pw.valaria.muhpackets.logger;

import net.minecraft.network.ConnectionProtocol;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.LastSeenMessages;
import net.minecraft.network.chat.MessageSignature;
import net.minecraft.network.chat.RemoteChatSession;
import net.minecraft.network.protocol.Packet;
import net.minecraft.world.phys.HitResult;
import org.jspecify.annotations.Nullable;

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
  /**
   * Fixed-width timestamp. ISO_LOCAL_DATE_TIME omits trailing zeros in the fractional second, so
   * the column width varied line to line, which is awkward to read and to parse.
   */
  private final static DateTimeFormatter TIMESTAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS");
  private final static DateTimeFormatter DEFAULT = DateTimeFormatter.ISO_LOCAL_DATE_TIME;

  /** Values longer than this are cut, so one oversized field cannot produce a megabyte line. */
  private final static int MAX_VALUE_LENGTH = 512;

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

  /**
   * Writes exactly one line:
   * {@code [timestamp] [protocol] [packet] key=value key=value}
   *
   * <p>The bracketed prefix is fixed width and easy to scan or split on; the payload is
   * space-separated {@code key=value} pairs rather than a Java map dump, so values containing
   * commas or braces are not ambiguous. Values are escaped and quoted as needed, which guarantees
   * one record never spans more than one line.</p>
   */
  public void write(Writer writer) throws IOException {
    final StringBuilder line = new StringBuilder(128);
    line.append('[').append(TIMESTAMP.format(this.time))
      .append("] [").append(protocol == null ? "UNKNOWN" : protocol.name())
      .append("] [").append(packetName).append(']');
    for (final Map.Entry<String, String> field : fields.entrySet()) {
      line.append(' ').append(field.getKey()).append('=');
      appendValue(line, field.getValue());
    }
    writer.write(line.append('\n').toString());
  }

  /**
   * Appends a value, quoting it when it would otherwise break the space-separated layout.
   */
  static void appendValue(StringBuilder out, String value) {
    final boolean quote = value.isEmpty() || needsQuoting(value);
    if (quote) {
      out.append('"');
    }
    for (int i = 0; i < value.length(); i++) {
      final char c = value.charAt(i);
      switch (c) {
        case '\\' -> out.append("\\\\");
        case '\n' -> out.append("\\n");
        case '\r' -> out.append("\\r");
        case '\t' -> out.append("\\t");
        case '"' -> out.append(quote ? "\\\"" : "\"");
        default -> {
          if (c < 0x20 || c == 0x7f) {
            out.append("\\u%04x".formatted((int) c));
          } else {
            out.append(c);
          }
        }
      }
    }
    if (quote) {
      out.append('"');
    }
  }

  private static boolean needsQuoting(String value) {
    for (int i = 0; i < value.length(); i++) {
      final char c = value.charAt(i);
      if (c == ' ' || c == '"' || c == '\\' || c < 0x20 || c == 0x7f) {
        return true;
      }
    }
    return false;
  }

  /**
   * Escapes a string for use in a log header, where the same one-line guarantee applies.
   */
  public static String escaped(String value) {
    final StringBuilder out = new StringBuilder(value.length() + 2);
    appendValue(out, value);
    return out.toString();
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
    return truncate(object.toString());
  }

  private static String truncate(String value) {
    if (value.length() <= MAX_VALUE_LENGTH) {
      return value;
    }
    return value.substring(0, MAX_VALUE_LENGTH) + "...(+" + (value.length() - MAX_VALUE_LENGTH) + " chars)";
  }
}
