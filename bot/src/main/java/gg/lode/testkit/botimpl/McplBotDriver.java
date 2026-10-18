package gg.lode.testkit.botimpl;

import gg.lode.testkit.bot.BotDriver;
import gg.lode.testkit.bot.FakePlayer;
import org.geysermc.mcprotocollib.protocol.codec.MinecraftCodec;

import java.time.Duration;

/** Loaded reflectively by {@code BotLoader} inside the bot's own classloader. */
public final class McplBotDriver implements BotDriver {

    @Override
    public String minecraftVersion() {
        return MinecraftCodec.CODEC.getMinecraftVersion();
    }

    @Override
    public FakePlayer connect(String host, int port, String name, Duration timeout) {
        McplFakePlayer player = new McplFakePlayer(host, port, name);
        player.connectAndSpawn(timeout);
        return player;
    }
}
