/*
 * Copyright (c) 2022-2026 CleanroomMC contributors
 * SPDX-License-Identifier: LGPL-3.0-only
 */

package com.cleanroommc.assetmover;

import com.cleanroommc.assetmover.api.GitHost;

import com.google.common.io.BaseEncoding;
import com.google.common.io.ByteStreams;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.jetbrains.annotations.ApiStatus;
import org.jspecify.annotations.Nullable;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.Reader;
import java.net.HttpURLConnection;
import java.net.SocketException;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.zip.GZIPInputStream;

@ApiStatus.Internal
public final class AssetMoverHelper {

    /**
     * Downloads run on this many threads. They are bound by latency rather than bandwidth, so there are plenty.
     */
    static final int THREADS = Integer.getInteger(
        "cleanroom.asset-mover.threads", Math.min(32, Math.max(8, Runtime.getRuntime().availableProcessors() * 4))
    );

    private static final String USER_AGENT = "Fetcher/@{VERSION} (+https://github.com/Michael-Sebero/Fetcher)";
    private static final int DEFAULT_TIMEOUT_SECONDS = 30;
    private static final int ATTEMPTS = Math.max(1, Integer.getInteger("cleanroom.asset-mover.attempts", 3));
    private static final int MAX_REDIRECTS = 5;
    private static final int BUFFER_SIZE = 64 * 1024;
    private static final long MAX_JSON = 64L * 1024 * 1024;
    private static final long MAX_DOWNLOAD = mebibytes("cleanroom.asset-mover.max-download", 1024);
    static final long MAX_ASSET = mebibytes("cleanroom.asset-mover.max-asset", 512);
    private static final boolean ALLOW_INSECURE = Boolean.getBoolean("cleanroom.asset-mover.allow-insecure");

    static {
        // Java keeps 5 idle connections per host by default, far too few to reuse them across this many threads,
        // so every other small file would pay for a new TLS handshake. Never overrides what the user set.
        if (System.getProperty("http.maxConnections") == null) {
            System.setProperty("http.maxConnections", String.valueOf(THREADS));
        }
    }

    private AssetMoverHelper() { }

    private static long mebibytes(String property, long fallback) {
        return Math.max(1, Long.getLong(property, fallback)) * 1024 * 1024;
    }

    public static String curseForge(String projectId, String fileId) {
        String artifact = "curse-" + projectId;
        return "https://curse.cleanroommc.com/curse/maven/" + artifact + "/" + fileId + "/" + artifact + "-" + fileId +
            ".jar";
    }

    public static String modrinth(String projectId, String versionId) throws IOException {
        JsonArray files = json(
            "https://api.modrinth.com/v2/project/" + projectId + "/version/" + versionId,
            "mod-site"
        ).getAsJsonArray("files");
        for (JsonElement file : files) {
            if (file.getAsJsonObject().get("primary").getAsBoolean()) {
                return file.getAsJsonObject().get("url").getAsString();
            }
        }
        return files.get(0).getAsJsonObject().get("url").getAsString();
    }

    public static GitHost gitea(String url) {
        String host = url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
        return new GitHost() {

            @Override
            public String archiveUrl(String repository, String ref) {
                return host + "/" + repository + "/archive/" + ref + ".zip";
            }

            @Override
            public String releaseUrl(String repository, String tag, String fileName) {
                return host + "/" + repository + "/releases/download/" + tag + "/" + fileName;
            }

        };
    }

    public static GitHost gitlab(String url) {
        String host = url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
        return new GitHost() {

            @Override
            public String archiveUrl(String repository, String ref) {
                String name = repository.substring(repository.lastIndexOf('/') + 1);
                return host + "/" + repository + "/-/archive/" + ref + "/" + name + "-" + ref.replace('/', '-') +
                    ".zip";
            }

            // GitLab release files are links rather than uploads, so they are looked up by name
            @Override
            public String releaseUrl(String repository, String tag, String fileName) throws IOException {
                JsonObject release = json(
                    host + "/api/v4/projects/" + URLEncoder.encode(repository, "UTF-8") + "/releases/" +
                        URLEncoder.encode(tag, "UTF-8"),
                    "git"
                );
                for (JsonElement element : release.getAsJsonObject("assets").getAsJsonArray("links")) {
                    JsonObject link = element.getAsJsonObject();
                    if (fileName.equals(link.get("name").getAsString())) {
                        JsonElement direct = link.get("direct_asset_url");
                        return (direct != null ? direct : link.get("url")).getAsString();
                    }
                }
                throw new IOException("Release " + tag + " of " + repository + " has no file named " + fileName);
            }

        };
    }

    // region JSON

    static JsonObject json(String url, String source) throws IOException {
        return retrying(url, () -> {
            HttpURLConnection connection = connect(url, source, true);
            InputStream in = connection.getInputStream();
            if ("gzip".equalsIgnoreCase(connection.getContentEncoding())) {
                in = new GZIPInputStream(in);
            }
            try (Reader reader = new InputStreamReader(ByteStreams.limit(in, MAX_JSON), StandardCharsets.UTF_8)) {
                return new JsonParser().parse(reader).getAsJsonObject();
            }
        });
    }

    static JsonObject json(Path file) throws IOException {
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            return new JsonParser().parse(reader).getAsJsonObject();
        }
    }

    // endregion

    // region Downloading

    /**
     * A file on disk that was hashed while it was written.
     */
    static final class Downloaded {

        final Path file;
        private final String algorithm;
        private final String hash;

        private Downloaded(Path file, String algorithm, String hash) {
            this.file = file;
            this.algorithm = algorithm;
            this.hash = hash;
        }

        String hash() {
            return hash;
        }

        /**
         * Hashes are compared without reading the file again when the algorithm is the one already used.
         */
        boolean matches(String algorithm, String checksum) throws IOException {
            return this.algorithm.equalsIgnoreCase(algorithm)
                ? hash.equalsIgnoreCase(checksum)
                : AssetMoverHelper.matches(file, algorithm, checksum);
        }

    }

    /**
     * Downloads {@code url} while hashing it, retrying what is worth retrying.
     * <p>Only https is accepted, and what is downloaded is capped in size. The file is complete or does not exist.
     *
     * @param directory where to put the file, which allows moving it into place atomically, or the system's
     * temporary directory when {@code null}
     * @param algorithm the algorithm to hash it with
     */
    static Downloaded download(String url, String source, @Nullable Path directory, String algorithm)
        throws IOException {
        long start = System.currentTimeMillis();
        Downloaded downloaded = retrying(url, () -> downloadOnce(url, source, directory, algorithm));
        AssetMover.LOGGER.info("Downloaded {} in {} ms", url, System.currentTimeMillis() - start);
        return downloaded;
    }

    private static Downloaded downloadOnce(String url, String source, @Nullable Path directory, String algorithm)
        throws IOException {
        HttpURLConnection connection = connect(url, source, false);
        long length = connection.getContentLengthLong();
        if (length > MAX_DOWNLOAD) {
            throw new IOException(url + " is " + length + " bytes, over the limit of " + MAX_DOWNLOAD);
        }
        MessageDigest digest = digest(algorithm);
        Path temp = directory == null ? Files.createTempFile("fetcher", ".download") : partFile(directory);
        boolean complete = false;
        try (InputStream in = connection.getInputStream(); OutputStream out = Files.newOutputStream(
            temp, StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING
        )) {
            long count = pump(in, out, digest, MAX_DOWNLOAD);
            // A connection that drops mid-body can look like a normal end of stream
            if (length >= 0 && count != length) {
                throw new EOFException("Got " + count + " of " + length + " bytes of " + url);
            }
            complete = true;
        } finally {
            if (!complete) {
                deleteQuietly(temp);
            }
        }
        return new Downloaded(temp, digest.getAlgorithm(), hex(digest));
    }

    /**
     * Opens a connection to a 2xx response. Redirects are followed by hand so every hop is checked as well.
     */
    private static HttpURLConnection connect(String spec, String source, boolean gzip) throws IOException {
        int timeout = (int) TimeUnit.SECONDS.toMillis(Integer.getInteger(
            "cleanroom.asset-mover." + source + ".timeout", DEFAULT_TIMEOUT_SECONDS
        ));
        URL url = checkUrl(spec);
        for (int hops = 0; hops <= MAX_REDIRECTS; hops++) {
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            connection.setInstanceFollowRedirects(false);
            connection.setConnectTimeout(timeout);
            connection.setReadTimeout(timeout);
            connection.setRequestProperty("User-Agent", USER_AGENT);
            if (gzip) {
                connection.setRequestProperty("Accept-Encoding", "gzip");
            }
            int code = connection.getResponseCode();
            if (code == 301 || code == 302 || code == 303 || code == 307 || code == 308) {
                String location = connection.getHeaderField("Location");
                drain(connection);
                if (location == null) {
                    throw new IOException("Redirect from " + url + " without a location");
                }
                url = checkUrl(new URL(url, location).toString());
                continue;
            }
            if (code < 200 || code >= 300) {
                drain(connection);
                throw new HttpStatusException(code, url);
            }
            return connection;
        }
        throw new IOException("Too many redirects from " + spec);
    }

    private static URL checkUrl(String spec) throws IOException {
        URL url = new URL(spec);
        String protocol = url.getProtocol();
        if ("https".equals(protocol)) {
            return url;
        }
        if ("http".equals(protocol) && (ALLOW_INSECURE || isLoopback(url.getHost()))) {
            return url;
        }
        throw new IOException(
            "Refusing " + url + ", only https is allowed (-Dcleanroom.asset-mover.allow-insecure=true permits http)"
        );
    }

    private static boolean isLoopback(String host) {
        return host.equals("localhost") || host.equals("127.0.0.1") || host.equals("[::1]");
    }

    /**
     * Reads what is left of an unused response so its connection can be reused.
     */
    private static void drain(HttpURLConnection connection) {
        try (InputStream in = connection.getResponseCode() >= 400
            ? connection.getErrorStream()
            : connection.getInputStream()) {
            if (in != null) {
                ByteStreams.exhaust(ByteStreams.limit(in, 64 * 1024));
            }
        } catch (IOException ignored) {
            // The response was not wanted in the first place
        }
    }

    private interface IOSupplier<T> {

        T get() throws IOException;

    }

    private static final class HttpStatusException extends IOException {

        private final int code;

        HttpStatusException(int code, URL url) {
            super("HTTP " + code + " from " + url);
            this.code = code;
        }

    }

    private static <T> T retrying(String what, IOSupplier<T> task) throws IOException {
        for (int attempt = 1;; attempt++) {
            try {
                return task.get();
            } catch (IOException e) {
                if (attempt >= ATTEMPTS || !transientFailure(e)) {
                    throw e;
                }
                AssetMover.LOGGER.warn("Attempt {}/{} at {} failed: {}", attempt, ATTEMPTS, what, e.toString());
                try {
                    Thread.sleep(attempt * 750L);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw e;
                }
            }
        }
    }

    /**
     * Timeouts are not retried, they are what the user configured to give up after.
     */
    private static boolean transientFailure(IOException e) {
        if (e instanceof HttpStatusException) {
            int code = ((HttpStatusException) e).code;
            return code == 429 || code == 500 || code == 502 || code == 503 || code == 504;
        }
        return e instanceof EOFException || e instanceof SocketException;
    }

    // endregion

    // region Hashing

    private static MessageDigest digest(String algorithm) {
        try {
            return MessageDigest.getInstance(algorithm);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalArgumentException("Unknown hash algorithm " + algorithm, e);
        }
    }

    private static String hex(MessageDigest digest) {
        return BaseEncoding.base16().lowerCase().encode(digest.digest());
    }

    /**
     * Copies {@code in} to {@code out}, which hashes it and enforces {@code limit} along the way.
     *
     * @return the number of bytes copied
     */
    private static long pump(InputStream in, OutputStream out, @Nullable MessageDigest digest, long limit)
        throws IOException {
        byte[] buffer = new byte[BUFFER_SIZE];
        long total = 0;
        for (int read; (read = in.read(buffer)) >= 0;) {
            total += read;
            if (total > limit) {
                throw new IOException("Over the size limit of " + limit + " bytes");
            }
            if (digest != null) {
                digest.update(buffer, 0, read);
            }
            out.write(buffer, 0, read);
        }
        return total;
    }

    static boolean matches(Path file, String algorithm, String checksum) throws IOException {
        MessageDigest digest = digest(algorithm);
        try (InputStream in = Files.newInputStream(file)) {
            pump(in, ByteStreams.nullOutputStream(), digest, Long.MAX_VALUE);
        }
        return hex(digest).equalsIgnoreCase(checksum);
    }

    static void verify(Downloaded downloaded, @Nullable String algorithm, @Nullable String checksum)
        throws IOException {
        if (checksum != null && algorithm != null && !downloaded.matches(algorithm, checksum)) {
            throw new IOException(algorithm + " checksum of the download does not match " + checksum);
        }
    }

    static String algorithmOf(String checksum) {
        switch (checksum.length()) {
            case 32:
                return "MD5";
            case 40:
                return "SHA-1";
            case 64:
                return "SHA-256";
            case 128:
                return "SHA-512";
            default:
                throw new IllegalArgumentException("Cannot tell the hash algorithm of " + checksum);
        }
    }

    // endregion

    // region Writing

    /**
     * Everything is written next to its destination under a name nothing looks at, then moved into place, so a
     * destination only ever holds a complete file. An interrupted launch cannot leave a truncated asset behind that
     * the next launch would take for a finished one.
     */
    private static Path partFile(Path directory) throws IOException {
        Files.createDirectories(directory);
        return directory.resolve(".fetcher-" + UUID.randomUUID() + ".part");
    }

    static void commit(Path temp, Path to) throws IOException {
        try {
            Files.move(temp, to, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(temp, to, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /**
     * Writes a stream to {@code to}, up to {@code limit} bytes.
     */
    static void store(InputStream in, Path to, long limit) throws IOException {
        Path temp = partFile(to.getParent());
        try {
            try (OutputStream out = Files.newOutputStream(temp, StandardOpenOption.CREATE, StandardOpenOption.WRITE)) {
                pump(in, out, null, limit);
            }
            commit(temp, to);
        } finally {
            deleteQuietly(temp);
        }
    }

    /**
     * Copies {@code from} to {@code to} only if it has the given hash, which it is checked against while copying.
     *
     * @return {@code false} if {@code from} is missing or has another hash, in which case nothing was written
     */
    static boolean copyVerified(Path from, Path to, String algorithm, String checksum) throws IOException {
        MessageDigest digest = digest(algorithm);
        Path temp = partFile(to.getParent());
        try {
            try (InputStream in = Files.newInputStream(from); OutputStream out = Files.newOutputStream(
                temp, StandardOpenOption.CREATE, StandardOpenOption.WRITE
            )) {
                pump(in, out, digest, Long.MAX_VALUE);
            } catch (NoSuchFileException e) {
                return false;
            }
            if (!hex(digest).equalsIgnoreCase(checksum)) {
                return false;
            }
            commit(temp, to);
            return true;
        } finally {
            deleteQuietly(temp);
        }
    }

    static void deleteQuietly(Path file) {
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            AssetMover.LOGGER.debug("Could not delete {}", file, e);
        }
    }

    // endregion

    static Path minecraftDirectory() {
        String os = System.getProperty("os.name").toLowerCase(Locale.ROOT);
        String home = System.getProperty("user.home");
        if (os.contains("win")) {
            String appData = System.getenv("APPDATA");
            return Paths.get(appData != null ? appData : home, ".minecraft");
        }
        if (os.contains("mac")) {
            return Paths.get(home, "Library", "Application Support", "minecraft");
        }
        return Paths.get(home, ".minecraft");
    }

}
