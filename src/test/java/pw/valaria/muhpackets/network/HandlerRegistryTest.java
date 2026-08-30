package pw.valaria.muhpackets.network;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.logging.Level;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class HandlerRegistryTest {

  private Logger logger;

  @BeforeEach
  void quietLogger() {
    logger = Logger.getAnonymousLogger();
    logger.setUseParentHandlers(false);
    logger.setLevel(Level.OFF);
  }

  /** Stands in for PacketLoggerHandler, which cannot be built without a running server. */
  private static final class TrackedHandler extends ChannelInboundHandlerAdapter {
    private final HandlerRegistry registry;

    TrackedHandler(HandlerRegistry registry) {
      this.registry = registry;
    }

    @Override
    public void handlerAdded(ChannelHandlerContext ctx) {
      registry.register(ctx);
    }

    @Override
    public void handlerRemoved(ChannelHandlerContext ctx) {
      registry.unregister(ctx);
    }
  }

  private EmbeddedChannel channelWith(HandlerRegistry registry) {
    final EmbeddedChannel channel = new EmbeddedChannel();
    channel.pipeline().addLast("muh_logger", new TrackedHandler(registry));
    return channel;
  }

  @Test
  void tracksHandlersAsTheyAreInstalled() {
    final HandlerRegistry registry = new HandlerRegistry();

    channelWith(registry);
    channelWith(registry);

    assertEquals(2, registry.tracked());
  }

  @Test
  void removesHandlersFromEveryTrackedPipeline() {
    final HandlerRegistry registry = new HandlerRegistry();
    final EmbeddedChannel one = channelWith(registry);
    final EmbeddedChannel two = channelWith(registry);
    assertNotNull(one.pipeline().get("muh_logger"));

    assertEquals(2, registry.removeAll(logger));

    assertNull(one.pipeline().get("muh_logger"), "handler left behind pins the plugin classloader");
    assertNull(two.pipeline().get("muh_logger"));
    assertEquals(0, registry.tracked());
  }

  @Test
  void closingAChannelStopsItBeingTracked() {
    // Otherwise the registry grows for the lifetime of the server, holding a context per
    // connection that has long since gone away.
    final HandlerRegistry registry = new HandlerRegistry();
    final EmbeddedChannel channel = channelWith(registry);
    assertEquals(1, registry.tracked());

    channel.close();

    assertEquals(0, registry.tracked());
  }

  @Test
  void handlerAlreadyRemovedIsNotAnError() {
    final HandlerRegistry registry = new HandlerRegistry();
    final EmbeddedChannel channel = channelWith(registry);
    // Re-register the context after removing it by hand, so removeAll meets one that has gone.
    final ChannelHandlerContext ctx = channel.pipeline().context("muh_logger");
    assertNotNull(ctx);
    channel.pipeline().remove("muh_logger");
    registry.register(ctx);

    assertEquals(0, registry.removeAll(logger), "nothing was removed, but nothing threw either");
    assertEquals(0, registry.tracked());
  }

  @Test
  void removeAllIsSafeToCallTwice() {
    final HandlerRegistry registry = new HandlerRegistry();
    channelWith(registry);

    assertEquals(1, registry.removeAll(logger));
    assertEquals(0, registry.removeAll(logger));
  }
}
