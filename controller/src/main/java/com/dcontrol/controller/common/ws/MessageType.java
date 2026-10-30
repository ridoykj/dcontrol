package com.dcontrol.controller.common.ws;

import java.util.Arrays;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Every message type of protocol v1 (docs/protocol/ws-v1.md). ai-context/sync.mjs reads this enum. */
public enum MessageType {
    DEVICE_HELLO("device.hello", "phone -> controller"),
    DEVICE_HEARTBEAT("device.heartbeat", "phone -> controller"),
    HELLO_ACK("hello.ack", "controller -> phone"),
    DEVICE_PING("device.ping", "web -> controller -> phone (command)"),
    DEVICE_PING_RESULT("device.ping.result", "phone -> controller -> web (result)"),
    DEVICES_SNAPSHOT("devices.snapshot", "controller -> web"),
    DEVICE_STATUS("device.status", "controller -> web"),
    PAIRING_COMPLETED("pairing.completed", "controller -> web"),
    DEVICE_INFO("device.info", "web -> controller -> phone (command)"),
    FILES_ROOTS("files.roots", "web -> controller -> phone (command)"),
    FILES_LIST("files.list", "web -> controller -> phone (command)"),
    FILES_MKDIR("files.mkdir", "web -> controller -> phone (command)"),
    FILES_RENAME("files.rename", "web -> controller -> phone (command)"),
    FILES_DELETE("files.delete", "web -> controller -> phone (command)"),
    NOTIFICATIONS_LIST("notifications.list", "web -> controller -> phone (command)"),
    NOTIFICATION_DISMISS("notification.dismiss", "web -> controller -> phone (command)"),
    NOTIFICATION_REPLY("notification.reply", "web -> controller -> phone (command)"),
    CLIPBOARD_SET("clipboard.set", "web -> controller -> phone (command)"),
    NOTIFICATION_POSTED("notification.posted", "phone -> controller -> web (event)"),
    NOTIFICATION_REMOVED("notification.removed", "phone -> controller -> web (event)"),
    CLIPBOARD_CHANGED("clipboard.changed", "phone -> controller -> web (event)"),
    TRANSFER_PROGRESS("transfer.progress", "phone -> controller -> web (event)"),
    TRANSFER_FINISHED("transfer.finished", "phone -> controller (event)"),
    TRANSFER_STATUS("transfer.status", "controller -> web"),
    TRANSFER_SEND("transfer.send", "controller -> phone (command)"),
    TRANSFER_RECEIVE("transfer.receive", "controller -> phone (command)"),
    TRANSFER_CANCEL("transfer.cancel", "controller -> phone (event)"),
    CONTACTS_LIST("contacts.list", "web -> controller -> phone (command)"),
    CONTACTS_GET("contacts.get", "web -> controller -> phone (command)"),
    CONTACTS_ACCOUNTS("contacts.accounts", "web -> controller -> phone (command)"),
    CONTACTS_CREATE("contacts.create", "web -> controller -> phone (command)"),
    CONTACTS_UPDATE("contacts.update", "web -> controller -> phone (command)"),
    CONTACTS_DELETE("contacts.delete", "web -> controller -> phone (command)"),
    CONTACTS_PHOTO("contacts.photo", "controller -> phone (command)"),
    CONTACTS_CHANGED("contacts.changed", "phone -> controller -> web (event)"),
    CALLS_LIST("calls.list", "web -> controller -> phone (command)"),
    CALLS_DELETE("calls.delete", "web -> controller -> phone (command)"),
    CALL_PLACE("call.place", "web -> controller -> phone (command)"),
    TELEPHONY_SIMS("telephony.sims", "web -> controller -> phone (command)"),
    CALLS_CHANGED("calls.changed", "phone -> controller -> web (event)"),
    SMS_THREADS("sms.threads", "web -> controller -> phone (command)"),
    SMS_MESSAGES("sms.messages", "web -> controller -> phone (command)"),
    SMS_SEND("sms.send", "web -> controller -> phone (command)"),
    SMS_RECEIVED("sms.received", "phone -> controller -> web (event)"),
    SMS_STATUS("sms.status", "phone -> controller -> web (event)"),
    SMS_CHANGED("sms.changed", "phone -> controller -> web (event)"),
    SCREEN_START("screen.start", "web -> controller -> phone (command)"),
    SCREEN_STATE("screen.state", "phone -> controller -> one web socket (event)"),
    SCREEN_STOP("screen.stop", "web -> controller -> phone (no result)"),
    RTC_SIGNAL("rtc.signal", "phone <-> controller <-> one web socket (no result)"),
    FIND_RING("find.ring", "web -> controller -> phone (command)"),
    FIND_LOCK("find.lock", "web -> controller -> phone (command)"),
    FIND_LOCATE("find.locate", "controller -> phone (command)"),
    CAMERA_START("camera.start", "web -> controller -> phone (command)"),
    CAMERA_STATE("camera.state", "phone -> controller -> one web socket (event)"),
    CAMERA_STOP("camera.stop", "web -> controller -> phone (no result)"),
    CAMERA_SIGNAL("camera.signal", "phone <-> controller <-> one web socket (no result)"),
    CAMERA_SWITCH("camera.switch", "web -> controller -> phone (command)"),
    CAMERA_CAPTURE("camera.capture", "web -> controller -> phone (command)"),
    ERROR("error", "controller -> phone | web");

    public static final String RESULT_SUFFIX = ".result";

    private static final Map<String, MessageType> BY_WIRE =
            Arrays.stream(values()).collect(Collectors.toUnmodifiableMap(MessageType::wire, Function.identity()));

    private final String wire;
    private final String direction;

    MessageType(String wire, String direction) {
        this.wire = wire;
        this.direction = direction;
    }

    public String wire() {
        return wire;
    }

    public String direction() {
        return direction;
    }

    public static Optional<MessageType> fromWire(String wire) {
        return Optional.ofNullable(BY_WIRE.get(wire));
    }
}
