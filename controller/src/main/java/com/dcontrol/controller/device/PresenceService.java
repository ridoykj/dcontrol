package com.dcontrol.controller.device;

import com.dcontrol.controller.common.config.AppProperties;
import com.dcontrol.controller.common.context.Actor;
import com.dcontrol.controller.common.context.EventChannel;
import com.dcontrol.controller.common.persistence.ActorTx;
import com.dcontrol.controller.common.ws.DeviceSessionRegistry;
import com.dcontrol.controller.common.ws.MessageCodec;
import com.dcontrol.controller.common.ws.MessageType;
import com.dcontrol.controller.common.ws.WebSessionRegistry;
import com.dcontrol.controller.device.DeviceProtocol.Battery;
import com.dcontrol.controller.device.DeviceProtocol.DeviceStatusView;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

/**
 * Online/offline and battery of devices. Live values are kept in memory; T_DEVICE_STATUS is written on
 * hello and disconnect and at most every {@code dcontrol.ws.status-persist-interval} from heartbeats.
 */
@Service
public class PresenceService {

    private record Live(UUID accountKey, Battery battery, Instant lastSeen, Instant lastPersisted) {
    }

    private final ConcurrentHashMap<UUID, Live> live = new ConcurrentHashMap<>();
    private final DeviceRepository devices;
    private final DeviceStatusRepository statuses;
    private final DeviceSessionRegistry deviceSessions;
    private final WebSessionRegistry webSessions;
    private final MessageCodec codec;
    private final ActorTx actorTx;
    private final Clock clock;
    private final Duration persistInterval;

    public PresenceService(DeviceRepository devices, DeviceStatusRepository statuses,
                           DeviceSessionRegistry deviceSessions, WebSessionRegistry webSessions, MessageCodec codec,
                           ActorTx actorTx, Clock clock, AppProperties properties) {
        this.devices = devices;
        this.statuses = statuses;
        this.deviceSessions = deviceSessions;
        this.webSessions = webSessions;
        this.codec = codec;
        this.actorTx = actorTx;
        this.clock = clock;
        this.persistInterval = properties.ws().statusPersistInterval();
    }

    /** Records a hello, refreshes device facts and returns the device name for {@code hello.ack}. */
    public String onHello(UUID accountKey, UUID deviceKey, DeviceProtocol.Hello hello) {
        Instant now = clock.instant();
        live.put(deviceKey, new Live(accountKey, hello.battery(), now, now));
        String name = actorTx.write(Actor.device(accountKey, deviceKey), () -> {
            Device device = devices.findByIdAndActive(deviceKey, true).orElseThrow();
            device.applyInfo(hello.info());
            persist(deviceKey, now, hello.battery());
            return device.getName();
        });
        broadcast(accountKey, deviceKey);
        return name;
    }

    public void onHeartbeat(UUID accountKey, UUID deviceKey, DeviceProtocol.Heartbeat heartbeat) {
        Instant now = clock.instant();
        Live previous = live.get(deviceKey);
        boolean persistDue = previous == null || !now.isBefore(previous.lastPersisted().plus(persistInterval));
        live.put(deviceKey, new Live(accountKey, heartbeat.battery(), now,
                persistDue ? now : previous.lastPersisted()));
        if (persistDue) {
            actorTx.writeVoid(Actor.device(accountKey, deviceKey), () -> persist(deviceKey, now, heartbeat.battery()));
        }
        if (previous == null || !Objects.equals(previous.battery(), heartbeat.battery())) {
            broadcast(accountKey, deviceKey);
        }
    }

    public void onDisconnect(UUID accountKey, UUID deviceKey) {
        Live previous = live.remove(deviceKey);
        if (previous != null) {
            Instant now = clock.instant();
            actorTx.writeVoid(Actor.device(accountKey, deviceKey), () -> persist(deviceKey, now, previous.battery()));
        }
        broadcast(accountKey, deviceKey);
    }

    /** Status of the current account's active devices; call inside a transaction of that account. */
    public List<DeviceStatusView> viewsOf(Collection<Device> owned) {
        Map<UUID, DeviceStatus> stored = statuses
                .findByDeviceKeyInAndActive(owned.stream().map(Device::getId).toList(), true).stream()
                .collect(Collectors.toMap(DeviceStatus::getDeviceKey, Function.identity()));
        return owned.stream().map(device -> view(device, stored.get(device.getId()))).toList();
    }

    public DeviceStatusView view(Device device, DeviceStatus stored) {
        Live current = live.get(device.getId());
        boolean online = device.isActive() && device.getState() == DeviceState.ACTIVE
                && deviceSessions.isOnline(device.getId());
        if (current != null) {
            return new DeviceStatusView(device.getId(), device.getName(), online, current.battery(), current.lastSeen());
        }
        if (stored == null) {
            return new DeviceStatusView(device.getId(), device.getName(), online, null, null);
        }
        Battery battery = stored.getBatteryLevel() == null || stored.getCharging() == null
                ? null
                : new Battery(stored.getBatteryLevel(), stored.getCharging());
        return new DeviceStatusView(device.getId(), device.getName(), online, battery, stored.getLastSeenAt());
    }

    public List<DeviceStatusView> snapshot(Actor user) {
        return actorTx.read(user, () -> viewsOf(devices.findByActiveOrderByCreatedAtDesc(true)));
    }

    /** Pushes the device's current status to every browser of its account. */
    public void broadcast(UUID accountKey, UUID deviceKey) {
        if (webSessions.count(accountKey) == 0) {
            return;
        }
        DeviceStatusView view = actorTx.read(Actor.system("presence", EventChannel.SYSTEM), () ->
                devices.findById(deviceKey)
                        .map(device -> view(device, statuses.findByDeviceKeyAndActive(deviceKey, true).orElse(null)))
                        .orElse(null));
        if (view != null) {
            webSessions.broadcast(accountKey, codec.message(MessageType.DEVICE_STATUS, null, view));
        }
    }

    public void forget(UUID deviceKey) {
        live.remove(deviceKey);
    }

    private void persist(UUID deviceKey, Instant now, Battery battery) {
        DeviceStatus status = statuses.findByDeviceKeyAndActive(deviceKey, true)
                .orElseGet(() -> new DeviceStatus(deviceKey, now));
        status.update(now, battery == null ? null : battery.level(), battery == null ? null : battery.charging());
        statuses.save(status);
    }
}
