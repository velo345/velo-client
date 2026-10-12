package net.veloclient.server;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Parties: a few friends playing together. In memory only - a party is a play session, not
 * something worth keeping across a server restart. Every change is pushed to the members through
 * the social long-poll as a {@code party} event carrying the full party view, so launcher and game
 * just replace what they show; chat, pings, shared waypoints and "launch with me" are their own
 * events ({@code party_chat}, {@code party_ping}, {@code party_waypoint}, {@code party_launch}).
 *
 * <p>Rules: you can only invite friends; anyone in the party may invite; the leader can kick,
 * hand leadership over, share their mod profile (members' launchers offer to sync to it) and call
 * everyone in to launch together. When the leader leaves, the longest-standing member leads.
 */
final class PartyService {

	static final int MAX_MEMBERS = 8;
	private static final long INVITE_MILLIS = 5 * 60_000;
	private static final int CHAT_KEEP = 100;
	private static final RateLimiter.Window[] CHAT_LIMIT = {new RateLimiter.Window(8, 5_000), new RateLimiter.Window(60, 60_000)};
	private static final RateLimiter.Window[] PING_LIMIT = {new RateLimiter.Window(6, 5_000), new RateLimiter.Window(60, 60_000)};
	private static final RateLimiter.Window[] MANAGE_LIMIT = {new RateLimiter.Window(30, 60_000)};

	record ChatLine(long id, String from, String name, String text, long time, String kind) {
	}

	/** The leader's mod profile, offered to the members' launchers. {@code mods}: name, file, url, sha1, projectId... */
	record ModSync(String profile, String mcVersion, List<Map<String, Object>> mods, long setAt) {
	}

	record LaunchCall(String server, String mcVersion, long at) {
	}

	record MemberView(String uuid, String name, boolean leader, boolean online, String activity, String detail) {
	}

	record InviteView(String party, String from, String fromName, String leaderName, int members, long expires) {
	}

	record PartyView(String id, String leader, List<MemberView> members, List<MemberView> invited, List<ChatLine> chat,
			ModSync sync, LaunchCall launch) {
	}

	record State(PartyView party, List<InviteView> invites) {
	}

	private static final class Party {
		final String id = UUID.randomUUID().toString().replace("-", "").substring(0, 10);
		String leader;
		final List<String> members = new ArrayList<>();
		/** invited uuid -> {expires, inviter} */
		final Map<String, Object[]> invites = new LinkedHashMap<>();
		final Deque<ChatLine> chat = new ArrayDeque<>();
		ModSync sync;
		LaunchCall launch;
	}

	private final SocialService social;
	private final Map<String, Party> parties = new HashMap<>();
	private final Map<String, String> partyOf = new HashMap<>();
	private long chatIds = System.currentTimeMillis();

	PartyService(SocialService social) {
		this.social = social;
	}

	// ---- reads ----

	synchronized State state(String me) {
		expireInvites();
		Party party = party(me);
		List<InviteView> invites = new ArrayList<>();
		for (Party p : parties.values()) {
			Object[] invite = p.invites.get(me);
			if (invite != null) {
				String from = (String) invite[1];
				invites.add(new InviteView(p.id, from, social.usernameOf(from), social.usernameOf(p.leader), p.members.size(), (long) invite[0]));
			}
		}
		return new State(party == null ? null : view(party), invites);
	}

	private PartyView view(Party p) {
		List<MemberView> members = new ArrayList<>();
		for (String uuid : p.members) {
			members.add(member(uuid, p.leader.equals(uuid)));
		}
		List<MemberView> invited = new ArrayList<>();
		for (String uuid : p.invites.keySet()) {
			invited.add(member(uuid, false));
		}
		List<ChatLine> chat = new ArrayList<>(p.chat);
		if (chat.size() > 50) {
			chat = chat.subList(chat.size() - 50, chat.size());
		}
		return new PartyView(p.id, p.leader, members, invited, chat, p.sync, p.launch);
	}

	private MemberView member(String uuid, boolean leader) {
		SocialService.Activity activity = social.activityOf(uuid);
		return new MemberView(uuid, social.usernameOf(uuid), leader, activity != null,
				activity == null ? null : activity.kind(), activity == null ? null : activity.detail());
	}

	private Party party(String uuid) {
		String id = partyOf.get(uuid);
		return id == null ? null : parties.get(id);
	}

	private Party requireParty(String me) throws SocialService.SocialException {
		Party party = party(me);
		if (party == null) {
			throw new SocialService.SocialException(400, "You're not in a party");
		}
		return party;
	}

	private Party requireLeader(String me) throws SocialService.SocialException {
		Party party = requireParty(me);
		if (!party.leader.equals(me)) {
			throw new SocialService.SocialException(403, "Only the party leader can do that");
		}
		return party;
	}

	private void expireInvites() {
		long now = System.currentTimeMillis();
		for (Party p : parties.values()) {
			p.invites.values().removeIf(invite -> (long) invite[0] < now);
		}
	}

	// ---- membership ----

	synchronized PartyView create(String me) throws SocialService.SocialException {
		Party existing = party(me);
		if (existing != null) {
			return view(existing);
		}
		Party party = new Party();
		party.leader = me;
		party.members.add(me);
		parties.put(party.id, party);
		partyOf.put(me, party.id);
		system(party, social.usernameOf(me) + " started the party");
		broadcast(party);
		return view(party);
	}

	synchronized PartyView invite(String me, String target) throws SocialService.SocialException {
		social.rateLimit(me, "party-manage", MANAGE_LIMIT);
		if (target.equals(me)) {
			throw new SocialService.SocialException(400, "You're already in your party");
		}
		if (!social.friendsWith(me, target)) {
			throw new SocialService.SocialException(403, "You can only invite friends");
		}
		Party party = party(me);
		if (party == null) {
			create(me);
			party = party(me);
		}
		if (party.members.contains(target)) {
			throw new SocialService.SocialException(400, social.usernameOf(target) + " is already in the party");
		}
		if (party.members.size() + party.invites.size() >= MAX_MEMBERS) {
			throw new SocialService.SocialException(400, "Parties are limited to " + MAX_MEMBERS + " players");
		}
		long expires = System.currentTimeMillis() + INVITE_MILLIS;
		party.invites.put(target, new Object[] {expires, me});
		social.pushEvent(target, "party_invite", new InviteView(party.id, me, social.usernameOf(me), social.usernameOf(party.leader),
				party.members.size(), expires));
		system(party, social.usernameOf(me) + " invited " + social.usernameOf(target));
		broadcast(party);
		return view(party);
	}

	synchronized PartyView respond(String me, String partyId, boolean accept) throws SocialService.SocialException {
		expireInvites();
		Party party = parties.get(partyId);
		if (party == null || party.invites.remove(me) == null) {
			throw new SocialService.SocialException(404, "That invite expired");
		}
		if (!accept) {
			system(party, social.usernameOf(me) + " declined the invite");
			broadcast(party);
			return null;
		}
		if (party(me) != null) {
			leave(me);
		}
		party.members.add(me);
		partyOf.put(me, party.id);
		system(party, social.usernameOf(me) + " joined the party");
		broadcast(party);
		return view(party);
	}

	synchronized void leave(String me) throws SocialService.SocialException {
		Party party = requireParty(me);
		removeMember(party, me, social.usernameOf(me) + " left the party");
		social.pushEvent(me, "party", new State(null, List.of()));
	}

	synchronized void kick(String me, String target) throws SocialService.SocialException {
		Party party = requireLeader(me);
		if (party.invites.remove(target) != null) {
			broadcast(party);
			return;
		}
		if (!party.members.contains(target) || target.equals(me)) {
			throw new SocialService.SocialException(400, "Not in your party");
		}
		removeMember(party, target, social.usernameOf(target) + " was removed from the party");
		social.pushEvent(target, "party", new State(null, List.of()));
	}

	private void removeMember(Party party, String uuid, String message) {
		party.members.remove(uuid);
		partyOf.remove(uuid);
		if (party.members.isEmpty()) {
			parties.remove(party.id);
			return;
		}
		if (party.leader.equals(uuid)) {
			party.leader = party.members.get(0);
			party.sync = null;
			party.launch = null;
			system(party, message + " - " + social.usernameOf(party.leader) + " leads now");
		} else {
			system(party, message);
		}
		broadcast(party);
	}

	synchronized void transfer(String me, String target) throws SocialService.SocialException {
		Party party = requireLeader(me);
		if (!party.members.contains(target)) {
			throw new SocialService.SocialException(400, "Not in your party");
		}
		party.leader = target;
		party.sync = null;
		party.launch = null;
		system(party, social.usernameOf(target) + " is the party leader now");
		broadcast(party);
	}

	// ---- leader tools ----

	synchronized void setSync(String me, ModSync sync) throws SocialService.SocialException {
		Party party = requireLeader(me);
		if (sync != null) {
			List<Map<String, Object>> mods = new ArrayList<>();
			if (sync.mods() != null) {
				for (Map<String, Object> mod : sync.mods()) {
					if (mods.size() >= 300) {
						break;
					}
					Map<String, Object> clean = new LinkedHashMap<>();
					for (String key : List.of("name", "file", "url", "sha1", "projectId", "versionId", "size")) {
						Object value = mod.get(key);
						if (value instanceof String s && s.length() > 300) {
							value = s.substring(0, 300);
						}
						if (value != null) {
							clean.put(key, value);
						}
					}
					mods.add(clean);
				}
			}
			sync = new ModSync(clip(sync.profile(), 60), clip(sync.mcVersion(), 20), mods, System.currentTimeMillis());
			system(party, social.usernameOf(me) + " asks everyone to use their mod profile \"" + sync.profile() + "\"");
		} else if (party.sync != null) {
			system(party, social.usernameOf(me) + " stopped sharing their mods");
		}
		party.sync = sync;
		broadcast(party);
	}

	synchronized void launch(String me, String server, String mcVersion) throws SocialService.SocialException {
		social.rateLimit(me, "party-manage", MANAGE_LIMIT);
		Party party = requireLeader(me);
		party.launch = new LaunchCall(clip(server, 120), clip(mcVersion, 20), System.currentTimeMillis());
		system(party, social.usernameOf(me) + " is launching" + (server == null || server.isBlank() ? "" : " - joining " + server));
		for (String member : party.members) {
			if (!member.equals(me)) {
				social.pushEvent(member, "party_launch", Map.of("party", party.id, "leader", me, "leaderName", social.usernameOf(me),
						"server", server == null ? "" : clip(server, 120), "mcVersion", mcVersion == null ? "" : clip(mcVersion, 20)));
			}
		}
		broadcast(party);
	}

	// ---- talking ----

	synchronized ChatLine chat(String me, String text) throws SocialService.SocialException {
		social.rateLimit(me, "party-chat", CHAT_LIMIT);
		Party party = requireParty(me);
		String clean = text == null ? "" : text.strip();
		if (clean.isEmpty()) {
			throw new SocialService.SocialException(400, "Empty message");
		}
		ChatLine line = add(party, new ChatLine(++chatIds, me, social.usernameOf(me), clip(clean, 500), System.currentTimeMillis(), "chat"));
		for (String member : party.members) {
			social.pushEvent(member, "party_chat", line);
		}
		return line;
	}

	/** A ping or shared waypoint, to everyone else in the party. {@code kind}: "ping" or "waypoint". */
	synchronized void share(String me, String kind, Map<String, Object> payload) throws SocialService.SocialException {
		social.rateLimit(me, "party-ping", PING_LIMIT);
		Party party = requireParty(me);
		Map<String, Object> clean = new LinkedHashMap<>();
		for (String key : List.of("name", "label", "x", "y", "z", "dimension", "world", "server", "color", "icon", "entity")) {
			Object value = payload == null ? null : payload.get(key);
			if (value instanceof String s && s.length() > 120) {
				value = s.substring(0, 120);
			}
			if (value != null) {
				clean.put(key, value);
			}
		}
		clean.put("from", me);
		clean.put("fromName", social.usernameOf(me));
		clean.put("time", System.currentTimeMillis());
		if ("waypoint".equals(kind)) {
			add(party, new ChatLine(++chatIds, me, social.usernameOf(me), "Shared a waypoint: " + clean.getOrDefault("name", "?"),
					System.currentTimeMillis(), "waypoint"));
		}
		for (String member : party.members) {
			if (!member.equals(me)) {
				social.pushEvent(member, "waypoint".equals(kind) ? "party_waypoint" : "party_ping", clean);
			}
		}
		if ("waypoint".equals(kind)) {
			broadcast(party);
		}
	}

	private ChatLine add(Party party, ChatLine line) {
		party.chat.addLast(line);
		while (party.chat.size() > CHAT_KEEP) {
			party.chat.removeFirst();
		}
		return line;
	}

	private void system(Party party, String text) {
		add(party, new ChatLine(++chatIds, null, null, text, System.currentTimeMillis(), "system"));
	}

	private void broadcast(Party party) {
		PartyView view = view(party);
		for (String member : party.members) {
			social.pushEvent(member, "party", new State(view, List.of()));
		}
	}

	/** Drops parties whose members have all been offline for a while (called periodically). */
	synchronized void sweep() {
		expireInvites();
		Iterator<Map.Entry<String, Party>> it = parties.entrySet().iterator();
		while (it.hasNext()) {
			Party party = it.next().getValue();
			boolean anyoneOnline = false;
			for (String member : party.members) {
				if (social.activityOf(member) != null) {
					anyoneOnline = true;
					break;
				}
			}
			if (!anyoneOnline) {
				for (String member : party.members) {
					partyOf.remove(member);
				}
				it.remove();
			}
		}
	}

	private static String clip(String text, int max) {
		if (text == null) {
			return null;
		}
		return text.length() > max ? text.substring(0, max) : text;
	}
}
