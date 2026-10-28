# Velo Client - Now Playing helper for Windows.
# Reads the system media session (the one Windows shows in its own media flyout) through
# GlobalSystemMediaTransportControls and prints it as one JSON line every 400 ms. Reads commands
# from stdin: toggle | next | prev | seek <ms> | vol <0..1>. Exits when stdin closes (the game quit).
# App volume goes through Core Audio (per-app session volume, the same slider as the Volume Mixer).
$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
[Console]::InputEncoding = [System.Text.Encoding]::UTF8

Add-Type -AssemblyName System.Runtime.WindowsRuntime
Add-Type -Language CSharp -TypeDefinition @'
using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.Runtime.InteropServices;
namespace VeloMedia {
	[ComImport, Guid("BCDE0395-E52F-467C-8E3D-C4579291692E")] class MMDeviceEnumeratorCom {}
	[ComImport, Guid("A95664D2-9614-4F35-A746-DE8DB63617E6"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
	interface IMMDeviceEnumerator {
		void EnumAudioEndpoints(int dataFlow, int stateMask, out IntPtr devices);
		void GetDefaultAudioEndpoint(int dataFlow, int role, out IMMDevice device);
	}
	[ComImport, Guid("D666063F-1587-4E43-81F1-B948E807363F"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
	interface IMMDevice {
		void Activate(ref Guid iid, int clsCtx, IntPtr activationParams, [MarshalAs(UnmanagedType.IUnknown)] out object iface);
	}
	[ComImport, Guid("77AA99A0-1BD6-484F-8BC7-2C654C9A9B6F"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
	interface IAudioSessionManager2 {
		void GetAudioSessionControl(IntPtr group, int flags, out IntPtr control);
		void GetSimpleAudioVolume(IntPtr group, int flags, out IntPtr volume);
		void GetSessionEnumerator(out IAudioSessionEnumerator sessions);
	}
	[ComImport, Guid("E2F5BB11-0570-40CA-ACDD-3AA01277DEE8"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
	interface IAudioSessionEnumerator {
		void GetCount(out int count);
		void GetSession(int index, out IAudioSessionControl2 session);
	}
	[ComImport, Guid("bfb7ff88-7239-4fc9-8fa2-07c950be9c6d"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
	interface IAudioSessionControl2 {
		void GetState(out int state);
		void GetDisplayName(out IntPtr name);
		void SetDisplayName(IntPtr name, IntPtr context);
		void GetIconPath(out IntPtr path);
		void SetIconPath(IntPtr path, IntPtr context);
		void GetGroupingParam(out Guid group);
		void SetGroupingParam(IntPtr group, IntPtr context);
		void RegisterAudioSessionNotification(IntPtr client);
		void UnregisterAudioSessionNotification(IntPtr client);
		void GetSessionIdentifier(out IntPtr id);
		void GetSessionInstanceIdentifier(out IntPtr id);
		void GetProcessId(out uint pid);
	}
	[ComImport, Guid("87CE5498-68D6-44E5-9215-6DA47EF883D8"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
	interface ISimpleAudioVolume {
		void SetMasterVolume(float level, ref Guid context);
		void GetMasterVolume(out float level);
	}
	public static class AppVolume {
		// Every audio session whose process name is part of the media app's id ("Spotify.exe", "Chrome", "SpotifyAB...!Spotify").
		static List<ISimpleAudioVolume> Find(string app) {
			var found = new List<ISimpleAudioVolume>();
			string want = (app ?? "").ToLowerInvariant();
			var devices = (IMMDeviceEnumerator) new MMDeviceEnumeratorCom();
			IMMDevice device;
			devices.GetDefaultAudioEndpoint(0, 1, out device);
			Guid iid = typeof(IAudioSessionManager2).GUID;
			object manager;
			device.Activate(ref iid, 23, IntPtr.Zero, out manager);
			IAudioSessionEnumerator sessions;
			((IAudioSessionManager2) manager).GetSessionEnumerator(out sessions);
			int count;
			sessions.GetCount(out count);
			for (int i = 0; i < count; i++) {
				IAudioSessionControl2 session;
				sessions.GetSession(i, out session);
				uint pid;
				session.GetProcessId(out pid);
				if (pid == 0) continue;
				string name;
				try { name = Process.GetProcessById((int) pid).ProcessName.ToLowerInvariant(); } catch { continue; }
				if (name.Length > 2 && want.Contains(name)) found.Add((ISimpleAudioVolume) session);
			}
			return found;
		}
		public static float Get(string app) {
			try {
				var all = Find(app);
				if (all.Count == 0) return -1;
				float level;
				all[0].GetMasterVolume(out level);
				return level;
			} catch { return -1; }
		}
		public static void Set(string app, float level) {
			try {
				Guid context = Guid.Empty;
				foreach (var v in Find(app)) v.SetMasterVolume(level, ref context);
			} catch { }
		}
	}
}
'@

$asTaskGeneric = ([System.WindowsRuntimeSystemExtensions].GetMethods() | Where-Object {
	$_.Name -eq 'AsTask' -and $_.GetParameters().Count -eq 1 -and $_.GetParameters()[0].ParameterType.Name -eq 'IAsyncOperation`1'
})[0]
function Await($op, [Type] $type) {
	$task = $asTaskGeneric.MakeGenericMethod($type).Invoke($null, @($op))
	[void] $task.Wait(5000)
	$task.Result
}

[void] [Windows.Media.Control.GlobalSystemMediaTransportControlsSessionManager, Windows.Media.Control, ContentType = WindowsRuntime]
[void] [Windows.Storage.Streams.DataReader, Windows.Storage.Streams, ContentType = WindowsRuntime]
$manager = Await ([Windows.Media.Control.GlobalSystemMediaTransportControlsSessionManager]::RequestAsync()) `
	([Windows.Media.Control.GlobalSystemMediaTransportControlsSessionManager])

$lastKey = $null
$lastProps = $null
$artPath = $null
$thumbTries = 0
$volumeAt = [DateTime]::MinValue
$volume = -1

function Save-Thumbnail($props, $key) {
	if ($script:artPath -and (Test-Path $script:artPath)) { Remove-Item $script:artPath -ErrorAction SilentlyContinue }
	$script:artPath = $null
	if (-not $props.Thumbnail) { return }
	try {
		$stream = Await ($props.Thumbnail.OpenReadAsync()) ([Windows.Storage.Streams.IRandomAccessStreamWithContentType])
		$size = [uint32] $stream.Size
		if ($size -le 0) { return }
		$reader = [Windows.Storage.Streams.DataReader]::new($stream.GetInputStreamAt(0))
		[void] (Await ($reader.LoadAsync($size)) ([uint32]))
		$bytes = New-Object byte[] $size
		$reader.ReadBytes($bytes)
		$reader.Dispose()
		$path = Join-Path $env:TEMP ('velo-cover-' + [Math]::Abs($key.GetHashCode()) + '.img')
		[System.IO.File]::WriteAllBytes($path, $bytes)
		$script:artPath = $path
	} catch { }
}

function Emit {
	$session = $manager.GetCurrentSession()
	if (-not $session) {
		[Console]::Out.WriteLine('none')
		return
	}
	$app = $session.SourceAppUserModelId
	$info = $session.GetPlaybackInfo()
	$timeline = $session.GetTimelineProperties()
	$props = $null
	try {
		$props = Await ($session.TryGetMediaPropertiesAsync()) ([Windows.Media.Control.GlobalSystemMediaTransportControlsSessionMediaProperties])
	} catch { }
	if (-not $props -or -not $props.Title) {
		[Console]::Out.WriteLine('none')
		return
	}
	$key = "$app|$($props.Title)|$($props.Artist)"
	if ($key -ne $script:lastKey) {
		$script:lastKey = $key
		$script:thumbTries = 0
		Save-Thumbnail $props $key
	} elseif (-not $script:artPath -and $script:thumbTries -lt 6) {
		# Spotify hands the cover over a moment after the title.
		$script:thumbTries++
		Save-Thumbnail $props $key
	}
	$playing = $info.PlaybackStatus -eq [Windows.Media.Control.GlobalSystemMediaTransportControlsSessionPlaybackStatus]::Playing
	$position = $timeline.Position.TotalMilliseconds
	if ($playing -and $timeline.LastUpdatedTime.Year -gt 2000) {
		$position += ([DateTimeOffset]::Now - $timeline.LastUpdatedTime).TotalMilliseconds
	}
	if (((Get-Date) - $script:volumeAt).TotalMilliseconds -gt 900) {
		$script:volume = [VeloMedia.AppVolume]::Get($app)
		$script:volumeAt = Get-Date
	}
	$out = [ordered] @{
		player   = $app
		title    = $props.Title
		artist   = $props.Artist
		album    = $props.AlbumTitle
		art      = $script:artPath
		length   = [long] $timeline.EndTime.TotalMilliseconds
		position = [long] $position
		playing  = $playing
		volume   = [double] $script:volume
		next     = $info.Controls.IsNextEnabled
		prev     = $info.Controls.IsPreviousEnabled
		seek     = $info.Controls.IsPlaybackPositionEnabled
	}
	[Console]::Out.WriteLine(($out | ConvertTo-Json -Compress))
}

function Run-Command([string] $line) {
	$session = $manager.GetCurrentSession()
	if (-not $session) { return }
	$parts = $line.Trim().Split(' ')
	switch ($parts[0]) {
		'toggle' { [void] (Await ($session.TryTogglePlayPauseAsync()) ([bool])) }
		'next' { [void] (Await ($session.TrySkipNextAsync()) ([bool])) }
		'prev' { [void] (Await ($session.TrySkipPreviousAsync()) ([bool])) }
		'seek' { [void] (Await ($session.TryChangePlaybackPositionAsync([long] $parts[1] * 10000)) ([bool])) }
		'vol' {
			$level = [float]::Parse($parts[1], [Globalization.CultureInfo]::InvariantCulture)
			[VeloMedia.AppVolume]::Set($session.SourceAppUserModelId, $level)
			$script:volume = $level
			$script:volumeAt = Get-Date
		}
	}
}

$stdin = [Console]::In
$pending = $stdin.ReadLineAsync()
while ($true) {
	$ran = $false
	while ($pending.IsCompleted) {
		$line = $pending.Result
		if ($null -eq $line) { exit 0 }
		try { Run-Command $line } catch { }
		$ran = $true
		$pending = $stdin.ReadLineAsync()
	}
	if ($ran) { Start-Sleep -Milliseconds 120 }
	try { Emit } catch { [Console]::Out.WriteLine('none') }
	[Console]::Out.Flush()
	[void] $pending.Wait(400)
}
