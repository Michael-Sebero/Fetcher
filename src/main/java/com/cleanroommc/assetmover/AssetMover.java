/*
 * Copyright (c) 2022-2026 CleanroomMC contributors
 * SPDX-License-Identifier: LGPL-3.0-only
 */

package com.cleanroommc.assetmover;

import com.cleanroommc.assetmover.api.AssetMoverAPI;
import com.cleanroommc.assetmover.api.AssetRequest;
import com.cleanroommc.assetmover.api.GitHost;
import com.cleanroommc.assetmover.api.ModSite;
import com.cleanroommc.assetmover.api.MoveAssetEvent;
import com.cleanroommc.assetmover.api.SingleAssetRequest;

import com.google.common.util.concurrent.ThreadFactoryBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jspecify.annotations.Nullable;

import net.minecraft.launchwrapper.Launch;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.ProgressManager;
import net.minecraftforge.fml.common.ProgressManager.ProgressBar;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

final class AssetMover implements AssetMoverAPI {

    static final Logger LOGGER = LogManager.getLogger("Fetcher");
    static final Path PACK = (Launch.minecraftHome == null ? new File(".") : Launch.minecraftHome)
        .toPath()
        .resolve("assetmover");

    private static final String VERSION_MANIFEST = "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json";
    private static final String RESOURCES = "https://resources.download.minecraft.net/";
    private static final String MAVEN_CENTRAL = "https://repo.maven.apache.org/maven2";
    private static final Pattern OBJECT_HASH = Pattern.compile("[0-9a-f]{40}");
    private static final Pattern VERSION = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._+ -]*");

    private static boolean movedClientAssets;

    private final List<Request> requests = new ArrayList<>();
    private final Map<List<?>, CompletableFuture<?>> shared = new ConcurrentHashMap<>();
    private final Set<Path> downloads = ConcurrentHashMap.newKeySet();

    private ExecutorService executor;

    /**
     * Only the first call does anything.
     */
    static void moveClientAssets() {
        if (movedClientAssets) {
            return;
        }
        movedClientAssets = true;
        move(MoveAssetEvent.Client::new);
    }

    /**
     * Fires the event and blocks until every queued asset is in place.
     */
    static void move(Function<AssetMoverAPI, MoveAssetEvent> event) {
        AssetMover mover = new AssetMover();
        MinecraftForge.EVENT_BUS.post(event.apply(mover));
        mover.run();
    }

    /**
     * Resolves a destination within the pack. A destination is never allowed to leave it.
     */
    static Path destination(String destination) {
        Path root = PACK.toAbsolutePath().normalize();
        Path resolved = root.resolve(destination).normalize();
        if (resolved.equals(root) || !resolved.startsWith(root)) {
            throw new IllegalArgumentException("Destination '" + destination + "' is not inside the pack");
        }
        return resolved;
    }

    private static void extract(Path archive, Map<String, String> assets, Object source) throws IOException {
        try (ZipFile zip = new ZipFile(archive.toFile())) {
            String root = null;
            boolean rootKnown = false;
            for (Map.Entry<String, String> asset : assets.entrySet()) {
                String name = asset.getKey();
                while (name.startsWith("/")) {
                    name = name.substring(1);
                }
                ZipEntry entry = zip.getEntry(name);
                if (entry == null) {
                    if (!rootKnown) {
                        root = singleRoot(zip);
                        rootKnown = true;
                    }
                    entry = root == null ? null : zip.getEntry(root + name);
                }
                if (entry == null || entry.isDirectory()) {
                    LOGGER.error("Could not find '{}' in {}", asset.getKey(), source);
                    continue;
                }
                if (entry.getSize() > AssetMoverHelper.MAX_ASSET) {
                    LOGGER.error("Skipping '{}' in {}, it is over the size limit", asset.getKey(), source);
                    continue;
                }
                try (InputStream in = zip.getInputStream(entry)) {
                    AssetMoverHelper.store(in, destination(asset.getValue()), AssetMoverHelper.MAX_ASSET);
                }
            }
        }
    }

    /**
     * Archives such as GitHub's wrap everything in one folder, which paths are allowed to omit.
     *
     * @return that folder with a trailing slash, or {@code null} if there is no such folder
     */
    private static @Nullable
    String singleRoot(ZipFile zip) {
        String root = null;
        for (Enumeration<? extends ZipEntry> entries = zip.entries(); entries.hasMoreElements();) {
            String name = entries.nextElement().getName();
            int slash = name.indexOf('/');
            if (slash < 0) {
                return null;
            }
            String folder = name.substring(0, slash + 1);
            if (root == null) {
                root = folder;
            } else if (!root.equals(folder)) {
                return null;
            }
        }
        return root;
    }

    private static void requireVersion(String version) {
        if (!VERSION.matcher(version).matches() || version.contains("..")) {
            throw new IllegalArgumentException("Invalid version '" + version + "'");
        }
    }

    @Override
    public AssetRequest fromJava(String version) {
        requireVersion(version);
        return new JavaAssets(version);
    }

    @Override
    public AssetRequest fromBedrock(String version) {
        requireVersion(version);
        return new Archive(
            "bedrock", Arrays.asList("bedrock", version), "Minecraft Bedrock " + version,
            () -> GitHost.GITHUB.archiveUrl("Mojang/bedrock-samples", "v" + version)
        );
    }

    @Override
    public AssetRequest fromModSite(ModSite site, String projectId, String fileId) {
        return new Archive(
            "mod-site", Arrays.asList(site, projectId, fileId), "mod site file " + projectId + "/" + fileId,
            () -> site.downloadUrl(projectId, fileId)
        );
    }

    @Override
    public AssetRequest fromGit(GitHost host, String repository, String ref) {
        return new Archive(
            "git", Arrays.asList(host, repository, ref), repository + "@" + ref,
            () -> host.archiveUrl(repository, ref)
        );
    }

    @Override
    public AssetRequest fromGitRelease(GitHost host, String repository, String tag, String fileName) {
        return new Archive(
            "git", Arrays.asList(host, repository, tag, fileName), repository + "@" + tag + "/" + fileName,
            () -> host.releaseUrl(repository, tag, fileName)
        );
    }

    @Override
    public AssetRequest fromMaven(String repositoryUrl, String coordinate) {
        String extension = "jar";
        int at = coordinate.indexOf('@');
        if (at >= 0) {
            extension = coordinate.substring(at + 1);
            coordinate = coordinate.substring(0, at);
        }
        String[] parts = coordinate.split(":");
        if (parts.length < 3 || parts.length > 4) {
            throw new IllegalArgumentException("Invalid maven coordinate " + coordinate);
        }
        String classifier = parts.length == 4 ? "-" + parts[3] : "";
        String repository = repositoryUrl.endsWith("/") ? repositoryUrl : repositoryUrl + "/";
        String url = repository + parts[0].replace('.', '/') + "/" + parts[1] + "/" + parts[2] + "/" + parts[1] + "-" +
            parts[2] + classifier + "." + extension;
        return new Archive("maven", Arrays.asList("url", url), url, () -> url);
    }

    @Override
    public AssetRequest fromMavenCentral(String coordinate) {
        return fromMaven(MAVEN_CENTRAL, coordinate);
    }

    @Override
    public AssetRequest fromUrl(String archiveUrl) {
        return new Archive("url", Arrays.asList("url", archiveUrl), archiveUrl, () -> archiveUrl);
    }

    @Override
    public SingleAssetRequest fromUrlFile(String fileUrl, String destination) {
        return new SingleFile(fileUrl, destination);
    }

    void run() {
        if (requests.isEmpty()) {
            return;
        }
        long start = System.currentTimeMillis();
        executor = Executors.newFixedThreadPool(
            AssetMoverHelper.THREADS, new ThreadFactoryBuilder().setNameFormat("Fetcher #%d").setDaemon(true).build()
        );
        BlockingQueue<Request> done = new LinkedBlockingQueue<>();
        for (Request request : requests) {
            request.start().whenComplete((result, t) -> {
                if (t != null) {
                    LOGGER.error("Could not move assets from {}", request, t);
                }
                done.add(request);
            });
        }
        // The splash screen renders progress bars from another thread, so they are only stepped from this one
        ProgressBar bar = ProgressManager.push("Fetcher", requests.size());
        try {
            for (int i = 1; i <= requests.size(); i++) {
                Request request = done.take();
                bar.step(request.toString());
                LOGGER.info("Finished {} ({}/{})", request, i, requests.size());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            ProgressManager.pop(bar);
            executor.shutdownNow();
            downloads.forEach(AssetMoverHelper::deleteQuietly);
            downloads.clear();
        }
        LOGGER.info("Moved assets in {} ms", System.currentTimeMillis() - start);
    }

    /**
     * Downloads to a temporary file that is deleted once everything is moved.
     */
    private AssetMoverHelper.Downloaded fetch(String url, String source) throws IOException {
        AssetMoverHelper.Downloaded downloaded = AssetMoverHelper.download(url, source, null, "SHA-256");
        downloads.add(downloaded.file);
        return downloaded;
    }

    private <T> CompletableFuture<T> async(Callable<T> task) {
        return CompletableFuture.supplyAsync(
            () -> {
                try {
                    return task.call();
                } catch (Exception e) {
                    throw new CompletionException(e);
                }
            },
            executor
        );
    }

    /**
     * Runs {@code task} once per {@code key}, so requests sharing a source share its download.
     */
    @SuppressWarnings("unchecked")
    private <T> CompletableFuture<T> shared(List<?> key, Callable<T> task) {
        return (CompletableFuture<T>) shared.computeIfAbsent(key, k -> async(task));
    }

    private abstract class Request {

        boolean overwrite;
        @Nullable String algorithm;
        @Nullable String checksum;

        Request() {
            requests.add(this);
        }

        abstract CompletableFuture<?> start();

        void setChecksum(String algorithm, String checksum) {
            this.algorithm = algorithm;
            this.checksum = checksum;
        }

    }

    private abstract class Assets extends Request implements AssetRequest {

        final Map<String, String> assets = new LinkedHashMap<>();

        @Override
        public AssetRequest move(String... paths) {
            return move(Arrays.asList(paths));
        }

        @Override
        public AssetRequest move(Collection<String> paths) {
            paths.forEach(path -> put(path, path));
            return this;
        }

        @Override
        public AssetRequest move(Map<String, String> assets) {
            assets.forEach(this::put);
            return this;
        }

        @Override
        public AssetRequest moveAs(String source, String destination) {
            put(source, destination);
            return this;
        }

        @Override
        public AssetRequest overwrite() {
            overwrite = true;
            return this;
        }

        @Override
        public AssetRequest verify(String checksum) {
            return verify(AssetMoverHelper.algorithmOf(checksum), checksum);
        }

        @Override
        public AssetRequest verify(String algorithm, String checksum) {
            setChecksum(algorithm, checksum);
            return this;
        }

        @Override
        final CompletableFuture<?> start() {
            Map<String, String> pending = new LinkedHashMap<>();
            assets.forEach((source, destination) -> {
                if (overwrite || !Files.exists(destination(destination))) {
                    pending.put(source, destination);
                }
            });
            return pending.isEmpty() ? CompletableFuture.completedFuture(null) : start(pending);
        }

        abstract CompletableFuture<?> start(Map<String, String> pending);

        private void put(String source, String destination) {
            destination(destination);
            assets.put(source, destination);
        }

    }

    private final class Archive extends Assets {

        private final String type;
        private final List<?> source;
        private final String description;
        private final Callable<String> url;

        Archive(String type, List<?> source, String description, Callable<String> url) {
            this.type = type;
            this.source = source;
            this.description = description;
            this.url = url;
        }

        @Override
        public String toString() {
            return description;
        }

        @Override
        CompletableFuture<?> start(Map<String, String> pending) {
            return shared(source, () -> fetch(url.call(), type)).thenCompose(downloaded -> async(() -> {
                AssetMoverHelper.verify(downloaded, algorithm, checksum);
                if (checksum == null) {
                    LOGGER.info("SHA-256 of {} is {}, pass it to verify() to pin it", description, downloaded.hash());
                }
                extract(downloaded.file, pending, this);
                return null;
            }));
        }

    }

    private final class JavaAssets extends Assets {

        private final String version;
        private final Path minecraft = AssetMoverHelper.minecraftDirectory();

        JavaAssets(String version) {
            this.version = version;
        }

        @Override
        public String toString() {
            return "Minecraft " + version;
        }

        @Override
        CompletableFuture<?> start(Map<String, String> pending) {
            return versionJson().thenCompose(json -> assetIndex(json).thenCompose(index -> {
                List<CompletableFuture<?>> tasks = new ArrayList<>();
                Map<String, String> fromJar = new LinkedHashMap<>();
                pending.forEach((source, destination) -> {
                    JsonObject object = index.getAsJsonObject(source);
                    if (object == null && source.startsWith("assets/")) {
                        object = index.getAsJsonObject(source.substring("assets/".length()));
                    }
                    if (object == null) {
                        fromJar.put(source, destination);
                    } else {
                        String hash = object.get("hash").getAsString();
                        if (!OBJECT_HASH.matcher(hash).matches()) {
                            LOGGER.error("Skipping '{}', its hash '{}' in the asset index is malformed", source, hash);
                            return;
                        }
                        tasks.add(async(() -> {
                            moveObject(hash, destination(destination));
                            return null;
                        }));
                    }
                });
                if (!fromJar.isEmpty()) {
                    tasks.add(
                        clientJar(json).thenCompose(jar -> async(() -> {
                            extract(jar, fromJar, this);
                            return null;
                        }))
                    );
                }
                return CompletableFuture.allOf(tasks.toArray(new CompletableFuture[0]));
            }));
        }

        private CompletableFuture<JsonObject> versionJson() {
            Path local = minecraft.resolve("versions").resolve(version).resolve(version + ".json");
            if (Files.exists(local)) {
                return shared(Arrays.asList("java", version), () -> AssetMoverHelper.json(local));
            }
            return shared(
                Arrays.asList("manifest"), () -> AssetMoverHelper.json(VERSION_MANIFEST, "java")
            ).thenCompose(manifest -> shared(Arrays.asList("java", version), () -> {
                for (JsonElement entry : manifest.getAsJsonArray("versions")) {
                    if (version.equals(entry.getAsJsonObject().get("id").getAsString())) {
                        return AssetMoverHelper.json(entry.getAsJsonObject().get("url").getAsString(), "java");
                    }
                }
                throw new IOException("Minecraft " + version + " is not in the version manifest");
            }));
        }

        private CompletableFuture<JsonObject> assetIndex(JsonObject json) {
            JsonObject info = json.getAsJsonObject("assetIndex");
            String id = info.get("id").getAsString();
            Path local = minecraft.resolve("assets").resolve("indexes").resolve(id + ".json");
            return shared(Arrays.asList("index", id), () -> {
                JsonObject index = Files.exists(local) ? AssetMoverHelper.json(local) : AssetMoverHelper.json(info.get("url").getAsString(), "java");
                return index.getAsJsonObject("objects");
            });
        }

        private void moveObject(String hash, Path destination) throws IOException {
            Path local = minecraft.resolve("assets").resolve("objects").resolve(hash.substring(0, 2)).resolve(hash);
            if (AssetMoverHelper.copyVerified(local, destination, "SHA-1", hash)) {
                return;
            }
            AssetMoverHelper.Downloaded downloaded = AssetMoverHelper.download(
                RESOURCES + hash.substring(0, 2) + "/" + hash, "java", destination.getParent(), "SHA-1"
            );
            try {
                AssetMoverHelper.verify(downloaded, "SHA-1", hash);
                AssetMoverHelper.commit(downloaded.file, destination);
            } finally {
                AssetMoverHelper.deleteQuietly(downloaded.file);
            }
        }

        private CompletableFuture<Path> clientJar(JsonObject json) {
            JsonObject client = json.getAsJsonObject("downloads").getAsJsonObject("client");
            String sha1 = client.get("sha1").getAsString();
            Path local = minecraft.resolve("versions").resolve(version).resolve(version + ".jar");
            return shared(Arrays.asList("client", version), () -> {
                if (Files.exists(local) && AssetMoverHelper.matches(local, "SHA-1", sha1)) {
                    return local;
                }
                AssetMoverHelper.Downloaded downloaded = fetch(client.get("url").getAsString(), "java");
                AssetMoverHelper.verify(downloaded, "SHA-1", sha1);
                return downloaded.file;
            });
        }

    }

    private final class SingleFile extends Request implements SingleAssetRequest {

        private final String url;
        private final Path to;

        SingleFile(String url, String destination) {
            this.url = url;
            this.to = destination(destination);
        }

        @Override
        public SingleAssetRequest overwrite() {
            overwrite = true;
            return this;
        }

        @Override
        public SingleAssetRequest verify(String checksum) {
            return verify(AssetMoverHelper.algorithmOf(checksum), checksum);
        }

        @Override
        public SingleAssetRequest verify(String algorithm, String checksum) {
            setChecksum(algorithm, checksum);
            return this;
        }

        @Override
        public String toString() {
            return url;
        }

        @Override
        CompletableFuture<?> start() {
            if (!overwrite && Files.exists(to)) {
                return CompletableFuture.completedFuture(null);
            }
            return async(() -> {
                AssetMoverHelper.Downloaded downloaded = AssetMoverHelper.download(
                    url, "url", to.getParent(), algorithm != null ? algorithm : "SHA-256"
                );
                try {
                    AssetMoverHelper.verify(downloaded, algorithm, checksum);
                    AssetMoverHelper.commit(downloaded.file, to);
                } finally {
                    AssetMoverHelper.deleteQuietly(downloaded.file);
                }
                return null;
            });
        }

    }

}
