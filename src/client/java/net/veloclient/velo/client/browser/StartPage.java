package net.veloclient.velo.client.browser;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;

/**
 * The Velo Browser's start page, built here and handed to Chromium as a data: URL (no request leaves the PC for it):
 * a search box (Google), shortcut tiles (letters on brand colours - no favicon lookups) and the background the player
 * picked. One grid that reflows from a tiny window to fullscreen.
 */
public final class StartPage {

	/** A shortcut tile: name, address, tile colour. */
	public record Shortcut(String name, String url, String color) {
	}

	public static final List<Shortcut> DEFAULTS = List.of(
			new Shortcut("YouTube", "https://www.youtube.com", "#FF0033"),
			new Shortcut("TikTok", "https://www.tiktok.com", "#111111"),
			new Shortcut("Twitch", "https://www.twitch.tv", "#9146FF"),
			new Shortcut("Instagram", "https://www.instagram.com", "#E1306C"),
			new Shortcut("Spotify", "https://open.spotify.com", "#1DB954"),
			new Shortcut("Google", "https://www.google.com", "#4285F4"),
			new Shortcut("Reddit", "https://www.reddit.com", "#FF4500"),
			new Shortcut("X", "https://x.com", "#222222"),
			new Shortcut("Discord", "https://discord.com/app", "#5865F2"),
			new Shortcut("Wiki", "https://minecraft.wiki", "#3C8527"));

	/** Background presets (CSS); "picture" uses the player's own file. */
	public static final List<String> BACKGROUNDS = List.of("Velo", "Midnight", "Sunset", "Ocean", "Forest", "Plain", "Picture");

	private StartPage() {
	}

	/** The start page as a data: URL. */
	public static String url(List<Shortcut> shortcuts, String background, Path picture) {
		String html = html(shortcuts, background, picture);
		return "data:text/html;charset=utf-8;base64," + Base64.getEncoder().encodeToString(html.getBytes(StandardCharsets.UTF_8));
	}

	/** What the address bar shows for the start page (the data: URL is long and meaningless). */
	public static boolean isStartPage(String url) {
		return url == null || url.isEmpty() || url.startsWith("data:text/html");
	}

	/** Text typed into the address bar: an address when it looks like one, else a Google search. */
	public static String resolve(String typed) {
		String t = typed.strip();
		if (t.isEmpty()) {
			return null;
		}
		if (t.matches("(?i)^(https?|file|about|data):.*")) {
			return t;
		}
		if (!t.contains(" ") && (t.matches("(?i)^[\\w-]+(\\.[\\w-]+)+(:\\d+)?(/.*)?$") || t.startsWith("localhost"))) {
			return "https://" + t;
		}
		return "https://www.google.com/search?q=" + java.net.URLEncoder.encode(t, StandardCharsets.UTF_8);
	}

	private static String css(String background, Path picture) {
		return switch (background) {
			case "Midnight" -> "radial-gradient(circle at 20% 10%, #1f2a55 0%, #0a0c18 55%, #05060c 100%)";
			case "Sunset" -> "linear-gradient(160deg, #ff7a59 0%, #c2185b 45%, #2a0a2f 100%)";
			case "Ocean" -> "linear-gradient(160deg, #00b4d8 0%, #0077b6 40%, #03045e 100%)";
			case "Forest" -> "linear-gradient(160deg, #3a7d44 0%, #1b4332 50%, #081c15 100%)";
			case "Plain" -> "#121014";
			case "Picture" -> pictureCss(picture);
			default -> "radial-gradient(circle at 80% 0%, rgba(224,38,46,.35) 0%, rgba(224,38,46,0) 45%), "
					+ "radial-gradient(circle at 0% 100%, rgba(139,108,255,.25) 0%, rgba(139,108,255,0) 50%), #0c0a0a";
		};
	}

	private static String pictureCss(Path picture) {
		try {
			if (picture != null && Files.isRegularFile(picture) && Files.size(picture) < 12L * 1024 * 1024) {
				String name = picture.getFileName().toString().toLowerCase(Locale.ROOT);
				String type = name.endsWith(".png") ? "png" : name.endsWith(".gif") ? "gif" : name.endsWith(".webp") ? "webp" : "jpeg";
				return "#0c0a0a url('data:image/" + type + ";base64," + Base64.getEncoder().encodeToString(Files.readAllBytes(picture))
						+ "') center / cover no-repeat";
			}
		} catch (Exception ignored) {
			// plain then
		}
		return "#0c0a0a";
	}

	private static String html(List<Shortcut> shortcuts, String background, Path picture) {
		List<String> tiles = new ArrayList<>();
		for (Shortcut s : shortcuts) {
			String letter = s.name().isEmpty() ? "?" : s.name().substring(0, 1).toUpperCase(Locale.ROOT);
			tiles.add("<a class=t href=\"" + esc(s.url()) + "\"><span class=i style=\"background:" + esc(s.color()) + "\">" + esc(letter)
					+ "</span><span class=n>" + esc(s.name()) + "</span></a>");
		}
		return """
				<!doctype html><html><head><meta charset=utf-8><meta name=viewport content="width=device-width,initial-scale=1">
				<title>New tab</title><style>
				*{box-sizing:border-box;margin:0;padding:0}
				html,body{height:100%%}
				body{background:%s;color:#ede6e6;font-family:Inter,"Segoe UI",system-ui,sans-serif;display:flex;align-items:center;justify-content:center;
				  overflow:auto;padding:clamp(12px,4vw,48px)}
				.w{width:min(760px,100%%);display:flex;flex-direction:column;align-items:center;gap:clamp(14px,3vh,30px)}
				h1{font-size:clamp(20px,4.2vw,40px);font-weight:800;letter-spacing:-.02em;text-shadow:0 2px 18px rgba(0,0,0,.45)}
				h1 b{color:#ff4d55}
				form{width:100%%;display:flex;position:relative}
				input{flex:1;min-width:0;font:inherit;font-size:clamp(13px,1.6vw,17px);color:#fff;background:rgba(20,18,24,.72);
				  border:1px solid rgba(255,255,255,.12);border-radius:999px;padding:clamp(9px,1.4vw,14px) clamp(46px,5.4vw,58px) clamp(9px,1.4vw,14px) clamp(14px,2vw,22px);outline:none;
				  backdrop-filter:blur(14px);transition:border-color .2s,box-shadow .2s}
				input:focus{border-color:rgba(255,77,85,.8);box-shadow:0 0 0 4px rgba(255,77,85,.18)}
				button{position:absolute;right:5px;top:50%%;transform:translateY(-50%%);height:calc(100%% - 10px);aspect-ratio:1;border:0;border-radius:999px;
				  background:#ff4d55;color:#fff;cursor:pointer;display:flex;align-items:center;justify-content:center;transition:filter .15s,transform .15s}
				button:hover{filter:brightness(1.12)}
				button:active{transform:translateY(-50%%) scale(.94)}
				button svg{width:46%%;height:46%%}
				.g{width:100%%;display:grid;grid-template-columns:repeat(auto-fill,minmax(clamp(64px,11vw,96px),1fr));gap:clamp(8px,1.6vw,16px)}
				.t{display:flex;flex-direction:column;align-items:center;gap:8px;text-decoration:none;color:#ede6e6;padding:10px 4px;border-radius:16px;
				  transition:background .18s,transform .18s}
				.t:hover{background:rgba(255,255,255,.08);transform:translateY(-2px)}
				.i{width:clamp(38px,5.4vw,54px);height:clamp(38px,5.4vw,54px);border-radius:14px;display:flex;align-items:center;justify-content:center;
				  font-weight:800;font-size:clamp(16px,2.4vw,24px);color:#fff;box-shadow:0 6px 18px rgba(0,0,0,.35)}
				.n{font-size:clamp(11px,1.2vw,13px);white-space:nowrap;overflow:hidden;text-overflow:ellipsis;max-width:100%%;opacity:.9}
				.p{font-size:clamp(10px,1.1vw,12px);opacity:.62;text-align:center;max-width:520px;line-height:1.5}
				@media (max-height:260px){h1,.p{display:none}}
				</style></head><body><div class=w>
				<h1>Velo <b>Browser</b></h1>
				<form action="https://www.google.com/search" onsubmit="return go(this)"><input name=q placeholder="Search Google or type an address" autofocus autocomplete=off><button type=submit aria-label=Search><svg viewBox="0 0 24 24" fill=none stroke=currentColor stroke-width=2.6 stroke-linecap=round><circle cx=10.5 cy=10.5 r=6.5 /><path d="M15.5 15.5 21 21"/></svg></button></form>
				<script>function go(f){var v=f.q.value.trim();if(!v)return false;
				if(/^[a-z]+:\\/\\//i.test(v)){location.href=v;return false}
				if(!/\\s/.test(v)&&/^[\\w-]+(\\.[\\w-]+)+(:\\d+)?(\\/.*)?$/.test(v)){location.href="https://"+v;return false}return true}</script>
				<div class=g>%s</div>
				<div class=p>Private: Velo saves nothing - no history, no cookies, no logins, no cache. Close the game and it's all gone.</div>
				</div></body></html>
				""".formatted(css(background, picture), String.join("", tiles));
	}

	private static String esc(String s) {
		return s.replace("&", "&amp;").replace("\"", "&quot;").replace("<", "&lt;").replace(">", "&gt;");
	}
}
