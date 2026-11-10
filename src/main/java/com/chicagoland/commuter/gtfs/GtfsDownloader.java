package com.chicagoland.commuter.gtfs;

import java.io.IOException;
import java.io.OutputStream;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.Optional;
import java.util.HexFormat;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/** Downloads GTFS zip files from the agencies' websites. */
@Component
public class GtfsDownloader {

    private final HttpClient http = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(30))
            .proxy(ProxySelector.getDefault())
            .build();

    /** Downloads the feed into {@code dir} as {@code <id>.zip}, replacing any older copy only on success. */
    public Path download(GtfsProperties.Feed feed, Path dir) throws IOException, InterruptedException {
        URI uri = feed.url() != null && !feed.url().isBlank() ? URI.create(feed.url()) : findZipLink(feed);
        Files.createDirectories(dir);
        Path part = dir.resolve(feed.id() + ".zip.part");
        Path target = dir.resolve(feed.id() + ".zip");

        HttpResponse<Path> response = http.send(request(uri), HttpResponse.BodyHandlers.ofFile(part));
        if (response.statusCode() != 200) {
            Files.deleteIfExists(part);
            throw new IOException(feed.name() + " download failed: HTTP " + response.statusCode() + " from " + uri);
        }
        return Files.move(part, target, StandardCopyOption.REPLACE_EXISTING);
    }

    /** Pace publishes a new link every month, so read their data page and pick out the zip link. */
    private URI findZipLink(GtfsProperties.Feed feed) throws IOException, InterruptedException {
        URI page = URI.create(feed.pageUrl());
        HttpResponse<String> response = http.send(request(page), HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IOException(feed.name() + " page failed: HTTP " + response.statusCode() + " from " + page);
        }
        return findLink(response.body(), feed.linkPattern(), response.uri())
                .orElseThrow(() -> new IOException("No GTFS zip link found on " + page));
    }

    static Optional<URI> findLink(String html, String linkPattern, URI base) {
        var matcher = Pattern.compile(linkPattern, Pattern.CASE_INSENSITIVE).matcher(html);
        return matcher.find() ? Optional.of(base.resolve(matcher.group(1))) : Optional.empty();
    }

    private static HttpRequest request(URI uri) {
        return HttpRequest.newBuilder(uri)
                .timeout(Duration.ofMinutes(10))
                .header("User-Agent", "chicagoland-commuter")
                .build();
    }

    /** Fingerprint of a file, used to skip re-importing a schedule that hasn't changed. */
    public static String sha256(Path file) throws IOException {
        try (var in = new DigestInputStream(Files.newInputStream(file), MessageDigest.getInstance("SHA-256"))) {
            in.transferTo(OutputStream.nullOutputStream());
            return HexFormat.of().formatHex(in.getMessageDigest().digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
