package net.veloclient.velo.client.clips;

/**
 * Windows "what you hear" without any extra software: WASAPI loopback capture of the default output device, called
 * straight through Java's foreign function API (Java 22+, so the 26.x builds; 1.21.11 runs on Java 21 and falls back
 * to the Stereo Mix input). COM is used through its vtables: IMMDeviceEnumerator -> default render endpoint ->
 * IAudioClient (shared mode, loopback flag) -> IAudioCaptureClient, polled every 5 ms. The mix format (normally 32-bit
 * float) is converted to 48 kHz stereo 16-bit for the mixer.
 */
final class WasapiLoopback {

	private WasapiLoopback() {
	}

	//? if <26.1 {
	static AudioCapture.Source open() {
		return null;
	}
	//?} else {
	/*private static final java.lang.foreign.Linker LINKER = java.lang.foreign.Linker.nativeLinker();
	private static final java.lang.foreign.ValueLayout.OfInt I32 = java.lang.foreign.ValueLayout.JAVA_INT;
	private static final java.lang.foreign.AddressLayout PTR = java.lang.foreign.ValueLayout.ADDRESS;
	// Downcall handles by function address (making one is slow; the capture loop calls the same few all the time).
	private static final java.util.Map<Long, java.lang.invoke.MethodHandle> HANDLES = new java.util.concurrent.ConcurrentHashMap<>();

	static AudioCapture.Source open() {
		if (!System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win")) {
			return null;
		}
		try {
			return new Source();
		} catch (Throwable t) {
			net.veloclient.velo.VeloClient.LOGGER.info("Clips Pro: WASAPI loopback unavailable ({})", t.toString());
			return null;
		}
	}

	private static java.lang.foreign.MemorySegment guid(java.lang.foreign.Arena arena, String text) {
		String[] p = text.split("-");
		java.lang.foreign.MemorySegment g = arena.allocate(16);
		g.set(I32, 0, (int) Long.parseLong(p[0], 16));
		g.set(java.lang.foreign.ValueLayout.JAVA_SHORT, 4, (short) Integer.parseInt(p[1], 16));
		g.set(java.lang.foreign.ValueLayout.JAVA_SHORT, 6, (short) Integer.parseInt(p[2], 16));
		String rest = p[3] + p[4];
		for (int i = 0; i < 8; i++) {
			g.set(java.lang.foreign.ValueLayout.JAVA_BYTE, 8 + i, (byte) Integer.parseInt(rest.substring(i * 2, i * 2 + 2), 16));
		}
		return g;
	}

	// Calls COM method {@code index} of {@code object} (its first argument is the object itself).
	private static int call(java.lang.foreign.MemorySegment object, int index, java.lang.foreign.FunctionDescriptor descriptor, Object... args) throws Throwable {
		java.lang.foreign.MemorySegment vtable = object.reinterpret(8).get(PTR, 0).reinterpret((index + 1) * 8L);
		java.lang.foreign.MemorySegment fn = vtable.get(PTR, index * 8L);
		java.lang.invoke.MethodHandle handle = HANDLES.computeIfAbsent(fn.address(), a -> LINKER.downcallHandle(fn, descriptor));
		Object[] all = new Object[args.length + 1];
		all[0] = object;
		System.arraycopy(args, 0, all, 1, args.length);
		return (int) handle.invokeWithArguments(all);
	}

	private static void check(int hr, String what) {
		if (hr < 0) {
			throw new IllegalStateException(what + " failed (0x" + Integer.toHexString(hr) + ")");
		}
	}

	private static final class Source extends AudioCapture.Source {
		private volatile boolean open = true;

		Source() throws Throwable {
			java.util.concurrent.CompletableFuture<Throwable> ready = new java.util.concurrent.CompletableFuture<>();
			Thread.ofPlatform().daemon().name("velo-clips-wasapi").start(() -> {
				try (java.lang.foreign.Arena arena = java.lang.foreign.Arena.ofConfined()) {
					java.lang.foreign.SymbolLookup ole = java.lang.foreign.SymbolLookup.libraryLookup("ole32", arena);
					java.lang.invoke.MethodHandle coInit = LINKER.downcallHandle(ole.find("CoInitializeEx").orElseThrow(),
							java.lang.foreign.FunctionDescriptor.of(I32, PTR, I32));
					java.lang.invoke.MethodHandle coCreate = LINKER.downcallHandle(ole.find("CoCreateInstance").orElseThrow(),
							java.lang.foreign.FunctionDescriptor.of(I32, PTR, PTR, I32, PTR, PTR));
					java.lang.invoke.MethodHandle coFree = LINKER.downcallHandle(ole.find("CoTaskMemFree").orElseThrow(),
							java.lang.foreign.FunctionDescriptor.ofVoid(PTR));
					coInit.invokeWithArguments(java.lang.foreign.MemorySegment.NULL, 0); // COINIT_MULTITHREADED
					java.lang.foreign.MemorySegment out = arena.allocate(PTR);
					check((int) coCreate.invokeWithArguments(guid(arena, "BCDE0395-E52F-467C-8E3D-C4579291692E"), java.lang.foreign.MemorySegment.NULL,
							0x17, guid(arena, "A95664D2-9614-4F35-A746-DE8DB63617E6"), out), "CoCreateInstance");
					java.lang.foreign.MemorySegment enumerator = out.get(PTR, 0);
					// IMMDeviceEnumerator::GetDefaultAudioEndpoint(eRender, eConsole, &device)
					check(call(enumerator, 4, java.lang.foreign.FunctionDescriptor.of(I32, PTR, I32, I32, PTR), 0, 0, out), "GetDefaultAudioEndpoint");
					java.lang.foreign.MemorySegment device = out.get(PTR, 0);
					// IMMDevice::Activate(IID_IAudioClient, CLSCTX_ALL, NULL, &client)
					check(call(device, 3, java.lang.foreign.FunctionDescriptor.of(I32, PTR, PTR, I32, PTR, PTR),
							guid(arena, "1CB9AD4C-DBFA-4C32-B178-C2F568A703B2"), 0x17, java.lang.foreign.MemorySegment.NULL, out), "Activate");
					java.lang.foreign.MemorySegment client = out.get(PTR, 0);
					// IAudioClient::GetMixFormat(&format)
					check(call(client, 8, java.lang.foreign.FunctionDescriptor.of(I32, PTR, PTR), out), "GetMixFormat");
					java.lang.foreign.MemorySegment format = out.get(PTR, 0).reinterpret(40);
					int formatTag = Short.toUnsignedInt(format.get(java.lang.foreign.ValueLayout.JAVA_SHORT, 0));
					int channels = Short.toUnsignedInt(format.get(java.lang.foreign.ValueLayout.JAVA_SHORT, 2));
					int rate = format.get(I32, 4);
					int blockAlign = Short.toUnsignedInt(format.get(java.lang.foreign.ValueLayout.JAVA_SHORT, 12));
					int bits = Short.toUnsignedInt(format.get(java.lang.foreign.ValueLayout.JAVA_SHORT, 14));
					// WAVE_FORMAT_IEEE_FLOAT (3), or EXTENSIBLE (0xFFFE) with 32 bits - Windows' shared mix format is float.
					boolean floats = formatTag == 3 || formatTag == 0xFFFE && bits == 32;
					// IAudioClient::Initialize(SHARED, LOOPBACK, 1 s buffer, 0, format, NULL)
					check(call(client, 3, java.lang.foreign.FunctionDescriptor.of(I32, PTR, I32, I32, java.lang.foreign.ValueLayout.JAVA_LONG,
							java.lang.foreign.ValueLayout.JAVA_LONG, PTR, PTR), 0, 0x00020000, 10_000_000L, 0L, format, java.lang.foreign.MemorySegment.NULL), "Initialize");
					// IAudioClient::GetService(IID_IAudioCaptureClient, &capture)
					check(call(client, 14, java.lang.foreign.FunctionDescriptor.of(I32, PTR, PTR, PTR), guid(arena, "C8ADBD64-E71E-48A0-A4DE-185C395CD317"), out),
							"GetService");
					java.lang.foreign.MemorySegment capture = out.get(PTR, 0);
					check(call(client, 10, java.lang.foreign.FunctionDescriptor.of(I32, PTR)), "Start");
					ready.complete(null);
					java.lang.foreign.MemorySegment data = arena.allocate(PTR);
					java.lang.foreign.MemorySegment frames = arena.allocate(I32);
					java.lang.foreign.MemorySegment flags = arena.allocate(I32);
					java.lang.foreign.MemorySegment packet = arena.allocate(I32);
					java.lang.foreign.FunctionDescriptor getBuffer = java.lang.foreign.FunctionDescriptor.of(I32, PTR, PTR, PTR, PTR, PTR, PTR);
					java.lang.foreign.FunctionDescriptor releaseBuffer = java.lang.foreign.FunctionDescriptor.of(I32, PTR, I32);
					java.lang.foreign.FunctionDescriptor nextPacket = java.lang.foreign.FunctionDescriptor.of(I32, PTR, PTR);
					double step = rate / (double) AudioCapture.RATE;
					double phase = 0;
					while (open) {
						check(call(capture, 5, nextPacket, packet), "GetNextPacketSize");
						if (packet.get(I32, 0) == 0) {
							Thread.sleep(5);
							continue;
						}
						check(call(capture, 3, getBuffer, data, frames, flags, java.lang.foreign.MemorySegment.NULL, java.lang.foreign.MemorySegment.NULL),
								"GetBuffer");
						int count = frames.get(I32, 0);
						boolean silent = (flags.get(I32, 0) & 2) != 0;
						java.lang.foreign.MemorySegment samples = data.get(PTR, 0).reinterpret((long) count * blockAlign);
						// Down/resample to 48 kHz stereo 16-bit (first two channels).
						int outFrames = (int) ((count - phase) / step);
						short[] pcm = new short[Math.max(0, outFrames) * 2];
						if (!silent) {
							for (int i = 0; i < outFrames; i++) {
								int src = Math.min(count - 1, (int) (phase + i * step));
								for (int c = 0; c < 2; c++) {
									int ch = Math.min(c, channels - 1);
									long at = (long) src * blockAlign + (long) ch * (bits / 8);
									float v = floats ? samples.get(java.lang.foreign.ValueLayout.JAVA_FLOAT_UNALIGNED, at)
											: samples.get(java.lang.foreign.ValueLayout.JAVA_SHORT_UNALIGNED, at) / 32768f;
									pcm[i * 2 + c] = (short) Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, Math.round(v * 32767)));
								}
							}
						}
						phase = phase + outFrames * step - count;
						check(call(capture, 4, releaseBuffer, count), "ReleaseBuffer");
						push(pcm);
					}
					call(client, 11, java.lang.foreign.FunctionDescriptor.of(I32, PTR));
					coFree.invokeWithArguments(format);
				} catch (Throwable t) {
					if (!ready.isDone()) {
						ready.complete(t);
					} else {
						net.veloclient.velo.VeloClient.LOGGER.debug("Clips Pro: WASAPI loopback stopped", t);
					}
				}
				dead = true;
			});
			Throwable failed = ready.get(5, java.util.concurrent.TimeUnit.SECONDS);
			if (failed != null) {
				throw failed;
			}
		}

		@Override
		void close() {
			open = false;
		}
	}
	*///?}
}
