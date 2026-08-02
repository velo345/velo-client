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

	private PacketTrafficTracker() {
	}

	public static void recordInbound(Class<?> packetClass) {
		INBOUND.computeIfAbsent(packetClass.getSimpleName(), k -> new LongAdder()).increment();
	}

	public static void recordOutbound(Class<?> packetClass) {
		OUTBOUND.computeIfAbsent(packetClass.getSimpleName(), k -> new LongAdder()).increment();
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
