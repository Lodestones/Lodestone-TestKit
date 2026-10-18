package gg.lode.testkit.botimpl;

import gg.lode.testkit.bot.FakePlayer;
import gg.lode.testkit.bot.OpenedContainer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.flattener.ComponentFlattener;
import org.geysermc.mcprotocollib.network.Session;
import org.geysermc.mcprotocollib.network.event.session.DisconnectedEvent;
import org.geysermc.mcprotocollib.network.event.session.SessionAdapter;
import org.geysermc.mcprotocollib.network.packet.Packet;
import org.geysermc.mcprotocollib.network.session.ClientNetworkSession;
import org.geysermc.mcprotocollib.protocol.MinecraftProtocol;
import org.geysermc.mcprotocollib.protocol.data.game.entity.player.PositionElement;
import org.geysermc.mcprotocollib.protocol.data.game.inventory.ClickItemAction;
import org.geysermc.mcprotocollib.protocol.data.game.inventory.ContainerActionType;
import org.geysermc.mcprotocollib.protocol.data.game.item.ItemStack;
import org.geysermc.mcprotocollib.protocol.data.game.item.component.DataComponentTypes;
import org.geysermc.mcprotocollib.protocol.data.game.item.component.DataComponents;
import org.geysermc.mcprotocollib.protocol.packet.common.clientbound.ClientboundDisconnectPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.ClientboundDisguisedChatPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.ClientboundPlayerChatPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.ClientboundSystemChatPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity.player.ClientboundPlayerPositionPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.inventory.ClientboundContainerClosePacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.inventory.ClientboundContainerSetContentPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.inventory.ClientboundContainerSetSlotPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.inventory.ClientboundOpenScreenPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.title.ClientboundSetActionBarTextPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.ServerboundChatCommandPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.ServerboundChatPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.ServerboundPlayerLoadedPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.inventory.ServerboundContainerClickPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.inventory.ServerboundContainerClosePacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.level.ServerboundAcceptTeleportationPacket;

import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

/** An offline-mode client built on MCProtocolLib, speaking the protocol of the pinned codec. */
final class McplFakePlayer implements FakePlayer {

    private final String host;
    private final int port;
    private final String name;
    private final List<String> messages = Collections.synchronizedList(new ArrayList<>());
    private final CountDownLatch spawned = new CountDownLatch(1);
    private final Object containerLock = new Object();
    // One thread per bot keeps packet handling in arrival order.
    private final ExecutorService packetExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "testkit-bot-packets");
        t.setDaemon(true);
        return t;
    });
    private ClientNetworkSession session;
    private volatile String disconnectReason;
    private volatile int sentMark;
    private volatile int containerId = -1;
    private volatile int stateId;
    private volatile String containerType;
    private volatile String containerTitle;
    private volatile List<String> containerSlots = List.of();
    private volatile boolean loadedSent;
    private double x;
    private double y;
    private double z;
    private float yaw;
    private float pitch;

    McplFakePlayer(String host, int port, String name) {
        this.host = host;
        this.port = port;
        this.name = name;
    }

    void connectAndSpawn(Duration timeout) {
        MinecraftProtocol protocol = new MinecraftProtocol(name);
        session = new ClientNetworkSession(new InetSocketAddress(host, port), protocol, packetExecutor, null, null);
        session.addListener(new Listener());
        session.connect(true);
        try {
            if (!spawned.await(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                String why = disconnectReason != null ? disconnectReason : "no spawn within " + timeout.toSeconds() + "s";
                close();
                throw new AssertionError(name + " could not join " + host + ":" + port + ": " + why);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            close();
            throw new AssertionError(name + " join interrupted", e);
        }
        if (disconnectReason != null) {
            throw new AssertionError(name + " was disconnected right after joining: " + disconnectReason);
        }
        awaitJoinBroadcast();
    }

    /** The join broadcast lands just after spawn; let it pass so it can't satisfy the first awaitMessage. */
    private void awaitJoinBroadcast() {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        synchronized (messages) {
            while (messages.stream().noneMatch(m -> m.contains("multiplayer.player.joined") || m.contains(name + " joined"))) {
                long left = deadline - System.nanoTime();
                if (left <= 0 || disconnectReason != null) break;
                try {
                    messages.wait(Math.max(1, TimeUnit.NANOSECONDS.toMillis(left)));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
            sentMark = messages.size();
        }
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public boolean isConnected() {
        return session != null && session.isConnected() && disconnectReason == null;
    }

    @Override
    public String disconnectReason() {
        return disconnectReason;
    }

    @Override
    public void command(String command) {
        requireConnected();
        sentMark = messages.size();
        session.send(new ServerboundChatCommandPacket(command.startsWith("/") ? command.substring(1) : command));
    }

    @Override
    public void chat(String message) {
        requireConnected();
        sentMark = messages.size();
        session.send(new ServerboundChatPacket(message, System.currentTimeMillis(), 0L, null, 0, new BitSet(20), 0));
    }

    @Override
    public List<String> messages() {
        synchronized (messages) {
            return List.copyOf(messages);
        }
    }

    @Override
    public int mark() {
        return messages.size();
    }

    @Override
    public List<String> messagesSince(int mark) {
        synchronized (messages) {
            return List.copyOf(messages.subList(Math.min(mark, messages.size()), messages.size()));
        }
    }

    @Override
    public String awaitMessage(Predicate<String> match, Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        int from = sentMark;
        synchronized (messages) {
            while (true) {
                for (int i = from; i < messages.size(); i++) {
                    if (match.test(messages.get(i))) return messages.get(i);
                }
                long left = deadline - System.nanoTime();
                if (left <= 0 || disconnectReason != null) {
                    throw new AssertionError(name + " got no matching message within " + timeout.toSeconds()
                            + "s" + (disconnectReason != null ? " (disconnected: " + disconnectReason + ")" : "")
                            + ". Received:\n" + String.join("\n", messages.subList(Math.min(from, messages.size()), messages.size())));
                }
                try {
                    messages.wait(Math.max(1, TimeUnit.NANOSECONDS.toMillis(left)));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError("Interrupted", e);
                }
            }
        }
    }

    @Override
    public OpenedContainer awaitContainer(Predicate<String> title, Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        synchronized (containerLock) {
            while (true) {
                OpenedContainer open = openContainer();
                if (open != null && title.test(open.title()) && !open.slots().isEmpty()) return open;
                long left = deadline - System.nanoTime();
                if (left <= 0 || disconnectReason != null) {
                    throw new AssertionError(name + " saw no matching container within " + timeout.toSeconds() + "s"
                            + (open != null ? " (open: '" + open.title() + "')" : ""));
                }
                try {
                    containerLock.wait(Math.max(1, TimeUnit.NANOSECONDS.toMillis(left)));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError("Interrupted", e);
                }
            }
        }
    }

    @Override
    public OpenedContainer openContainer() {
        int id = containerId;
        if (id < 0) return null;
        return new OpenedContainer(id, containerType, containerTitle, containerSlots);
    }

    @Override
    public void click(int slot) {
        requireConnected();
        int id = containerId;
        if (id < 0) throw new IllegalStateException(name + " has no container open");
        session.send(new ServerboundContainerClickPacket(id, stateId, slot, ContainerActionType.CLICK_ITEM,
                ClickItemAction.LEFT_CLICK, null, Collections.emptyMap()));
    }

    @Override
    public void closeContainer() {
        int id = containerId;
        if (id < 0) return;
        session.send(new ServerboundContainerClosePacket(id));
        clearContainer();
    }

    @Override
    public void close() {
        if (session != null && session.isConnected()) session.disconnect("TestKit done");
        packetExecutor.shutdown();
    }

    private void requireConnected() {
        if (!isConnected()) {
            throw new IllegalStateException(name + " is not connected" + (disconnectReason != null ? ": " + disconnectReason : ""));
        }
    }

    private void addMessage(Component component) {
        StringBuilder text = new StringBuilder();
        ComponentFlattener.basic().flatten(component, text::append);
        synchronized (messages) {
            messages.add(text.toString());
            messages.notifyAll();
        }
    }

    private void clearContainer() {
        synchronized (containerLock) {
            containerId = -1;
            containerSlots = List.of();
            containerLock.notifyAll();
        }
    }

    private static String describe(ItemStack stack) {
        if (stack == null || stack.getAmount() <= 0) return null;
        DataComponents patch = stack.getDataComponentsPatch();
        if (patch != null) {
            Component custom = patch.get(DataComponentTypes.CUSTOM_NAME);
            if (custom == null) custom = patch.get(DataComponentTypes.ITEM_NAME);
            if (custom != null) {
                StringBuilder text = new StringBuilder();
                ComponentFlattener.basic().flatten(custom, text::append);
                return text.toString();
            }
        }
        return "item:" + stack.getId();
    }

    private final class Listener extends SessionAdapter {
        @Override
        public void packetReceived(Session s, Packet packet) {
            switch (packet) {
                case ClientboundPlayerPositionPacket pos -> {
                    // 26.x acks a teleport with the resulting position, so track it (relative axes add on).
                    List<PositionElement> rel = pos.getRelatives();
                    x = rel.contains(PositionElement.X) ? x + pos.getPosition().getX() : pos.getPosition().getX();
                    y = rel.contains(PositionElement.Y) ? y + pos.getPosition().getY() : pos.getPosition().getY();
                    z = rel.contains(PositionElement.Z) ? z + pos.getPosition().getZ() : pos.getPosition().getZ();
                    yaw = rel.contains(PositionElement.Y_ROT) ? yaw + pos.getYRot() : pos.getYRot();
                    pitch = rel.contains(PositionElement.X_ROT) ? pitch + pos.getXRot() : pos.getXRot();
                    s.send(new ServerboundAcceptTeleportationPacket(pos.getId(), x, y, z, yaw, pitch));
                    if (!loadedSent) {
                        loadedSent = true;
                        s.send(ServerboundPlayerLoadedPacket.INSTANCE);
                    }
                    spawned.countDown();
                }
                case ClientboundSystemChatPacket chat -> addMessage(chat.getContent());
                case ClientboundPlayerChatPacket chat -> {
                    Component content = chat.getUnsignedContent() != null ? chat.getUnsignedContent() : Component.text(chat.getContent());
                    addMessage(content);
                }
                case ClientboundDisguisedChatPacket chat -> addMessage(chat.getMessage());
                case ClientboundSetActionBarTextPacket bar -> {
                    if (bar.getText() != null) addMessage(bar.getText());
                }
                case ClientboundOpenScreenPacket open -> {
                    synchronized (containerLock) {
                        StringBuilder title = new StringBuilder();
                        ComponentFlattener.basic().flatten(open.getTitle(), title::append);
                        containerId = open.getContainerId();
                        containerType = String.valueOf(open.getType());
                        containerTitle = title.toString();
                        containerSlots = List.of();
                        containerLock.notifyAll();
                    }
                }
                case ClientboundContainerSetContentPacket content -> {
                    if (content.getContainerId() != containerId) return;
                    synchronized (containerLock) {
                        stateId = content.getStateId();
                        ItemStack[] items = content.getItems();
                        // The packet carries the container plus the player's 36 inventory slots.
                        int size = Math.max(0, items.length - 36);
                        List<String> slots = new ArrayList<>(size);
                        for (int i = 0; i < size; i++) slots.add(describe(items[i]));
                        containerSlots = Collections.unmodifiableList(slots);
                        containerLock.notifyAll();
                    }
                }
                case ClientboundContainerSetSlotPacket slot -> {
                    if (slot.getContainerId() != containerId) return;
                    synchronized (containerLock) {
                        stateId = slot.getStateId();
                        List<String> slots = new ArrayList<>(containerSlots);
                        if (slot.getSlot() >= 0 && slot.getSlot() < slots.size()) {
                            slots.set(slot.getSlot(), describe(slot.getItem()));
                            containerSlots = Collections.unmodifiableList(slots);
                        }
                        containerLock.notifyAll();
                    }
                }
                case ClientboundContainerClosePacket close -> clearContainer();
                case ClientboundDisconnectPacket disconnect -> {
                    StringBuilder reason = new StringBuilder();
                    ComponentFlattener.basic().flatten(disconnect.getReason(), reason::append);
                    disconnectReason = reason.toString();
                }
                default -> {
                }
            }
        }

        @Override
        public void disconnected(DisconnectedEvent event) {
            if (disconnectReason == null) {
                StringBuilder reason = new StringBuilder();
                ComponentFlattener.basic().flatten(event.getReason(), reason::append);
                disconnectReason = reason.isEmpty() ? "connection closed" : reason.toString();
                if (event.getCause() != null) disconnectReason += " (" + event.getCause() + ")";
            }
            spawned.countDown();
            synchronized (messages) {
                messages.notifyAll();
            }
            synchronized (containerLock) {
                containerLock.notifyAll();
            }
        }
    }

    @Override
    public String toString() {
        return "FakePlayer[" + name + "@" + host + ":" + port + "]";
    }
}
