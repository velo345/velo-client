package net.veloclient.velo.client.util;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

/**
 * Purely observational packet counters, by packet class simple name. Never
 * inspects packet contents, never drops/delays/mutates anything - counts only
 * (design spec section 6.3's Packet Traffic Monitor, "purely observational,
 * no packet modification").
 */
public final class PacketTrafficTracker {

	private static final Map<String, LongAdder> INBOUND = new ConcurrentHashMap<>();
	private static final Map<String, LongAdder> OUTBOUND = new ConcurrentHashMap<>();
	// Class#getSimpleName() walks the class's enclosing-class chain every call, so it's cached
	// here instead of being re-resolved for every single packet - this map is read on the
	// hot path (every packet in and out) whenever the module is enabled.
	private static final Map<Class<?>, String> SIMPLE_NAMES = new ConcurrentHashMap<>();

	/**
	 * Gates both mixin injection points ({@link net.veloclient.velo.client.mixin.ClientConnectionMixin})
	 * so this costs nothing on the packet hot path while the Packet Traffic Monitor module is
	 * disabled - previously the mixin ran unconditionally for every packet regardless of whether
	 * anything was listening.
	 */
	private static volatile boolean enabled;

	private PacketTrafficTracker() {
	}

	public static void setEnabled(boolean value) {
		enabled = value;
	}

	public static void recordInbound(Class<?> packetClass) {
		if (!enabled) {
			return;
		}
		INBOUND.computeIfAbsent(simpleName(packetClass), k -> new LongAdder()).increment();
	}

	public static void recordOutbound(Class<?> packetClass) {
		if (!enabled) {
			return;
		}
		OUTBOUND.computeIfAbsent(simpleName(packetClass), k -> new LongAdder()).increment();
	}

	private static String simpleName(Class<?> packetClass) {
		return SIMPLE_NAMES.computeIfAbsent(packetClass, Class::getSimpleName);
	}

	public static Map<String, Long> inboundSnapshot() {
		return snapshot(INBOUND);
	}

	public static Map<String, Long> outboundSnapshot() {
		return snapshot(OUTBOUND);
	}

	public static void reset() {
		INBOUND.clear();
		OUTBOUND.clear();
	}

	private static Map<String, Long> snapshot(Map<String, LongAdder> source) {
		Map<String, Long> result = new java.util.LinkedHashMap<>();
		source.forEach((key, value) -> result.put(key, value.sum()));
		return result;
	}
}
