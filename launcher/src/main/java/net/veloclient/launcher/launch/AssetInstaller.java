package net.veloclient.launcher.launch;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.LongConsumer;

/** Downloads a version's asset index and every object it references into the shared assets cache. */
public final class AssetInstaller {

	private static final String RESOURCES_BASE_URL = "https://resources.download.minecraft.net/";

	private AssetInstaller() {
	}

	public record AssetIndexInfo(String id, String url, String sha1, long size) {
	}

	public record AssetObject(String hash, long size) {
	}

	public static AssetIndexInfo readAssetIndexInfo(JsonObject versionDetail) {
		JsonObject assetIndex = versionDetail.getAsJsonObject("assetIndex");
		return new AssetIndexInfo(
				assetIndex.get("id").getAsString(),
				assetIndex.get("url").getAsString(),
				assetIndex.has("sha1") ? assetIndex.get("sha1").getAsString() : null,
				assetIndex.has("size") ? assetIndex.get("size").getAsLong() : -1);
	}

	/** Downloads the (small) asset index itself and returns every object it references, so callers can size a progress bar before downloading. */
	public static List<AssetObject> loadIndex(AssetIndexInfo indexInfo) throws IOException {
		Path indexFile = GameDataPaths.assetIndexes().resolve(indexInfo.id() + ".json");
		Downloader.ensure(URI.create(indexInfo.url()), indexFile, indexInfo.sha1(), indexInfo.size(), b -> { });

		JsonObject index;
		try (var reader = Files.newBufferedReader(indexFile)) {
			index = JsonParser.parseReader(reader).getAsJsonObject();
		}
		JsonObject objects = index.getAsJsonObject("objects");
		return objects.asMap().values().stream()
				.map(el -> {
					JsonObject o = el.getAsJsonObject();
					return new AssetObject(o.get("hash").getAsString(), o.get("size").getAsLong());
				})
				.toList();
	}

	public static void downloadAll(List<AssetObject> assets, LongConsumer onBytes) throws IOException {
		ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
		try {
			List<Future<?>> futures = assets.stream()
					.<Future<?>>map(asset -> executor.submit((Callable<Void>) () -> {
						String prefix = asset.hash().substring(0, 2);
						Path dest = GameDataPaths.assetObjects().resolve(prefix).resolve(asset.hash());
						Downloader.ensure(URI.create(RESOURCES_BASE_URL + prefix + "/" + asset.hash()), dest, asset.hash(), asset.size(), onBytes);
						return null;
					}))
					.toList();
			for (Future<?> future : futures) {
				future.get();
			}
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IOException("Asset download interrupted", e);
		} catch (ExecutionException e) {
			Throwable cause = e.getCause();
			throw cause instanceof IOException io ? io : new IOException("Failed to download assets", cause);
		} finally {
			executor.shutdown();
		}
	}
}
