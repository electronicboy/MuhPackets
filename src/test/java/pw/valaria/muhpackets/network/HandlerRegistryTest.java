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
      if (!registry.register(ctx)) {
        // Mirrors PacketLoggerHandler: refused registration means take yourself back out.
        ctx.pipeline().remove(this);
      }
    }

    @Override
    public void handlerRemoved(ChannelHandlerContext ctx) {
      registry.unregister(ctx);
    }
  }

  private HandlerRegistry openRegistry() {
    final HandlerRegistry registry = new HandlerRegistry();
    registry.open();
    return registry;
  }

  private EmbeddedChannel channelWith(HandlerRegistry registry) {
    final EmbeddedChannel channel = new EmbeddedChannel();
    channel.pipeline().addLast("muh_logger", new TrackedHandler(registry));
    return channel;
  }

  @Test
  void tracksHandlersAsTheyAreInstalled() {
    final HandlerRegistry registry = openRegistry();

    channelWith(registry);
    channelWith(registry);

    assertEquals(2, registry.tracked());
  }

  @Test
  void removesHandlersFromEveryTrackedPipeline() {
    final HandlerRegistry registry = openRegistry();
    final EmbeddedChannel one = channelWith(registry);
    final EmbeddedChannel two = channelWith(registry);
    assertNotNull(one.pipeline().get("muh_logger"));

    assertEquals(2, registry.shutdown(logger));

    assertNull(one.pipeline().get("muh_logger"), "handler left behind pins the plugin classloader");
    assertNull(two.pipeline().get("muh_logger"));
    assertEquals(0, registry.tracked());
  }

  @Test
  void closingAChannelStopsItBeingTracked() {
    // Otherwise the registry grows for the lifetime of the server, holding a context per
    // connection that has long since gone away.
    final HandlerRegistry registry = openRegistry();
    final EmbeddedChannel channel = channelWith(registry);
    assertEquals(1, registry.tracked());

    channel.close();

    assertEquals(0, registry.tracked());
  }

  @Test
  void handlerAlreadyRemovedIsNotAnError() {
    final HandlerRegistry registry = openRegistry();
    final EmbeddedChannel channel = channelWith(registry);
    // Re-register the context after removing it by hand, so removeAll meets one that has gone.
    final ChannelHandlerContext ctx = channel.pipeline().context("muh_logger");
    assertNotNull(ctx);
    channel.pipeline().remove("muh_logger");
    registry.register(ctx);

    assertEquals(0, registry.shutdown(logger), "nothing was removed, but nothing threw either");
    assertEquals(0, registry.tracked());
  }

  @Test
  void registrationIsRefusedOnceShutDown() {
    // A connection accepted while onDisable is sweeping would otherwise install a handler that
    // nothing will ever remove - holding the dead plugin's classloader open for the life of that
    // connection, which is the leak this class exists to prevent.
    final HandlerRegistry registry = openRegistry();
    registry.shutdown(logger);

    final EmbeddedChannel late = channelWith(registry);

    assertNull(late.pipeline().get("muh_logger"), "a handler installed after shutdown must not stay");
    assertEquals(0, registry.tracked());
  }

  @Test
  void refusedRegistrationIsNotTracked() {
    final HandlerRegistry registry = openRegistry();
    registry.shutdown(logger);

    channelWith(registry);
    channelWith(registry);

    assertEquals(0, registry.tracked(), "refused handlers must not accumulate in the registry");
  }

  @Test
  void nothingIsAdmittedBeforeTheGateOpens() {
    // The listener goes on last during enable, but the ordering guarantee should not rest on that
    // alone: a handler installed before the plugin finished enabling has nothing behind it yet.
    final HandlerRegistry registry = new HandlerRegistry();

    final EmbeddedChannel early = channelWith(registry);

    assertNull(early.pipeline().get("muh_logger"));
    assertEquals(0, registry.tracked());
  }

  @Test
  void shutdownIsTerminal() {
    // A reload builds a new plugin and a new registry, so reopening this one would only ever mean
    // handlers registering into a plugin that is already gone.
    final HandlerRegistry registry = openRegistry();
    registry.shutdown(logger);

    registry.open();

    assertEquals(0, registry.tracked());
    channelWith(registry);
    assertEquals(0, registry.tracked(), "open() must not resurrect a shut-down registry");
  }

  @Test
  void aRegistrationRacingTheSweepIsRefused() {
    // The ordering guarantee itself: the signal is cleared BEFORE the sweep, so a connection
    // arriving while it runs is refused rather than slipping in behind it. Driven reentrantly from
    // inside handlerRemoved, which is precisely "mid-sweep". Move the state change below the loop
    // and this fails.
    final HandlerRegistry registry = openRegistry();
    final EmbeddedChannel[] latecomer = new EmbeddedChannel[1];
    final EmbeddedChannel sweeping = new EmbeddedChannel();
    sweeping.pipeline().addLast("muh_logger", new ChannelInboundHandlerAdapter() {
      @Override
      public void handlerAdded(ChannelHandlerContext ctx) {
        if (!registry.register(ctx)) {
          ctx.pipeline().remove(this);
        }
      }

      @Override
      public void handlerRemoved(ChannelHandlerContext ctx) {
        registry.unregister(ctx);
        if (latecomer[0] == null) {
          // A connection arriving while the sweep is still running.
          latecomer[0] = channelWith(registry);
        }
      }
    });

    registry.shutdown(logger);

    assertNotNull(latecomer[0], "the sweep should have admitted the attempt");
    assertNull(latecomer[0].pipeline().get("muh_logger"),
      "a handler registered mid-sweep would never be removed by anything");
    assertEquals(0, registry.tracked());
  }

  @Test
  void shutdownIsSafeToCallTwice() {
    final HandlerRegistry registry = openRegistry();
    channelWith(registry);

    assertEquals(1, registry.shutdown(logger));
    assertEquals(0, registry.shutdown(logger));
  }
}
