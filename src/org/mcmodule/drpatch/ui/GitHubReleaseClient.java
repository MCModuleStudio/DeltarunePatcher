package org.mcmodule.drpatch.ui;

import org.mcmodule.util.downloader.HTTPDownloader;
import org.mcmodule.util.downloader.IEProxySupport;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.URL;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;

class GitHubReleaseClient implements PatchDownloadSource {
    private static final URI RELEASES_URI = URI.create("https://api.github.com/repos/gm3dr/DeltaruneChinese/releases");
    private static final int DOWNLOAD_CONNECTIONS = 4;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .proxy(IEProxySupport.getProxySelector())
            .build();

    @Override
    public String name() {
        return "国外安装源 (GitHub Releases)";
    }

    @Override
    public String toString() {
        return name();
    }

    @Override
    public List<PatchDownloadSource.Release> listReleases() throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(RELEASES_URI)
                .timeout(Duration.ofSeconds(30))
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", "DeltaruneChinesePatcher")
                .GET()
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException("GitHub Releases 请求失败: HTTP " + response.statusCode());
        }
        Object root = new JsonParser(response.body()).parse();
        if (!(root instanceof List<?>)) {
            throw new IOException("GitHub Releases 响应格式无效");
        }
        List<PatchDownloadSource.Release> releases = new ArrayList<>();
        for (Object releaseValue : (List<?>) root) {
            if (!(releaseValue instanceof Map<?, ?> releaseMap)) {
                continue;
            }
            String tagName = stringValue(releaseMap.get("tag_name"));
            String name = stringValue(releaseMap.get("name"));
            String publishedAt = stringValue(releaseMap.get("published_at"));
            Object assetsValue = releaseMap.get("assets");
            List<PatchDownloadSource.Asset> assets = new ArrayList<>();
            if (assetsValue instanceof List<?> assetList) {
                for (Object assetValue : assetList) {
                    if (!(assetValue instanceof Map<?, ?> assetMap)) {
                        continue;
                    }
                    String assetName = stringValue(assetMap.get("name"));
                    String downloadUrl = stringValue(assetMap.get("browser_download_url"));
                    long size = longValue(assetMap.get("size"));
                    if (!assetName.isEmpty() && !downloadUrl.isEmpty()) {
                        assets.add(new PatchDownloadSource.Asset(assetName, downloadUrl, size));
                    }
                }
            }
            if (!tagName.isEmpty() && !assets.isEmpty()) {
                releases.add(new PatchDownloadSource.Release(name.isEmpty() ? tagName : name, tagName, publishedAt, assets));
            }
        }
        return releases;
    }

    @Override
    public Path download(PatchDownloadSource.Asset asset, Path target, PatchDownloadSource.ProgressCallback progressCallback) throws IOException, InterruptedException {
        Objects.requireNonNull(asset, "asset");
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(progressCallback, "progressCallback");

        Path temporaryTarget = target.resolveSibling(target.getFileName() + ".download");
        ProgressHTTPDownloader downloader = new ProgressHTTPDownloader(temporaryTarget.toFile(), new URL(asset.location()), DOWNLOAD_CONNECTIONS);
        long previousBytes = 0;
        long previousTime = System.nanoTime();
        downloader.start();
        while (!downloader.awaitCompletion(200)) {
            long currentBytes = downloader.getDownloadedBytes();
            long currentTime = System.nanoTime();
            progressCallback.update(new PatchDownloadSource.DownloadProgress(downloader.getProgressPercent(), calculateSpeed(previousBytes, currentBytes, previousTime, currentTime)));
            previousBytes = currentBytes;
            previousTime = currentTime;
            if (Thread.currentThread().isInterrupted()) {
                throw new InterruptedException();
            }
        }
        if (downloader.isFailed()) {
            IOException failureCause = downloader.getFailureCause();
            throw new IOException("已达到最大重试次数", failureCause);
        }
        if (!downloader.isCompleted()) {
            throw new IOException("下载未完成");
        }
        Files.move(temporaryTarget, target, StandardCopyOption.REPLACE_EXISTING);
        progressCallback.update(new PatchDownloadSource.DownloadProgress(100, 0));
        return target;
    }

    private static long calculateSpeed(long previousBytes, long currentBytes, long previousTime, long currentTime) {
        long elapsedNanos = currentTime - previousTime;
        if (elapsedNanos <= 0) {
            return 0;
        }
        return (currentBytes - previousBytes) * 1_000_000_000L / elapsedNanos;
    }

    private static class ProgressHTTPDownloader extends HTTPDownloader {
        private final CountDownLatch completedLatch = new CountDownLatch(1);

        ProgressHTTPDownloader(File file, URL url, int numConnections, URL... backupURLs) throws IOException {
            super(file, url, numConnections, backupURLs);
        }

        int getProgressPercent() {
            if (size <= 0 || progress == null || blocks <= 0) {
                return 0;
            }
            long completedBlocks = getCompletedBlocks();
            return (int) Math.min(100, completedBlocks * 100 / blocks);
        }

        long getDownloadedBytes() {
            if (size <= 0 || progress == null) {
                return 0;
            }
            return Math.min(size, getCompletedBlocks() * blockSize);
        }

        private long getCompletedBlocks() {
            long completedBlocks = 0;
            for (int i = 0; i < progress.length; i++) {
                completedBlocks += progress[i].cardinality();
            }
            return completedBlocks;
        }

        boolean awaitCompletion(long millis) throws InterruptedException {
            return completedLatch.await(millis, java.util.concurrent.TimeUnit.MILLISECONDS);
        }

        @Override
        protected void downloadCompleted() {
            completedLatch.countDown();
        }
    }

    private static String stringValue(Object value) {
        return value instanceof String ? (String) value : "";
    }

    private static long longValue(Object value) {
        return value instanceof Number ? ((Number) value).longValue() : 0L;
    }

    private static class JsonParser {
        private final String json;
        private int index;

        JsonParser(String json) {
            this.json = json;
        }

        Object parse() throws IOException {
            Object value = parseValue();
            skipWhitespace();
            if (index != json.length()) {
                throw error("JSON 末尾存在多余内容");
            }
            return value;
        }

        private Object parseValue() throws IOException {
            skipWhitespace();
            if (index >= json.length()) {
                throw error("JSON 意外结束");
            }
            char ch = json.charAt(index);
            if (ch == '{') {
                return parseObject();
            }
            if (ch == '[') {
                return parseArray();
            }
            if (ch == '"') {
                return parseString();
            }
            if (ch == 't') {
                expect("true");
                return Boolean.TRUE;
            }
            if (ch == 'f') {
                expect("false");
                return Boolean.FALSE;
            }
            if (ch == 'n') {
                expect("null");
                return null;
            }
            if (ch == '-' || Character.isDigit(ch)) {
                return parseNumber();
            }
            throw error("无法识别的 JSON 字符: " + ch);
        }

        private Map<String, Object> parseObject() throws IOException {
            expect('{');
            Map<String, Object> object = new LinkedHashMap<>();
            skipWhitespace();
            if (peek('}')) {
                index++;
                return object;
            }
            while (true) {
                skipWhitespace();
                String key = parseString();
                skipWhitespace();
                expect(':');
                object.put(key, parseValue());
                skipWhitespace();
                if (peek('}')) {
                    index++;
                    return object;
                }
                expect(',');
            }
        }

        private List<Object> parseArray() throws IOException {
            expect('[');
            List<Object> array = new ArrayList<>();
            skipWhitespace();
            if (peek(']')) {
                index++;
                return array;
            }
            while (true) {
                array.add(parseValue());
                skipWhitespace();
                if (peek(']')) {
                    index++;
                    return array;
                }
                expect(',');
            }
        }

        private String parseString() throws IOException {
            expect('"');
            StringBuilder builder = new StringBuilder();
            while (index < json.length()) {
                char ch = json.charAt(index++);
                if (ch == '"') {
                    return builder.toString();
                }
                if (ch == '\\') {
                    if (index >= json.length()) {
                        throw error("JSON 字符串转义意外结束");
                    }
                    char escape = json.charAt(index++);
                    switch (escape) {
                    case '"':
                    case '\\':
                    case '/':
                        builder.append(escape);
                        break;
                    case 'b':
                        builder.append('\b');
                        break;
                    case 'f':
                        builder.append('\f');
                        break;
                    case 'n':
                        builder.append('\n');
                        break;
                    case 'r':
                        builder.append('\r');
                        break;
                    case 't':
                        builder.append('\t');
                        break;
                    case 'u':
                        builder.append(parseUnicodeEscape());
                        break;
                    default:
                        throw error("无效 JSON 转义字符: " + escape);
                    }
                } else {
                    builder.append(ch);
                }
            }
            throw error("JSON 字符串未闭合");
        }

        private char parseUnicodeEscape() throws IOException {
            if (index + 4 > json.length()) {
                throw error("JSON Unicode 转义意外结束");
            }
            String digits = json.substring(index, index + 4);
            index += 4;
            try {
                return (char) Integer.parseInt(digits, 16);
            } catch (NumberFormatException e) {
                throw error("无效 JSON Unicode 转义: " + digits);
            }
        }

        private Number parseNumber() throws IOException {
            int start = index;
            if (peek('-')) {
                index++;
            }
            while (index < json.length() && Character.isDigit(json.charAt(index))) {
                index++;
            }
            if (peek('.')) {
                index++;
                while (index < json.length() && Character.isDigit(json.charAt(index))) {
                    index++;
                }
            }
            if (index < json.length() && (json.charAt(index) == 'e' || json.charAt(index) == 'E')) {
                index++;
                if (index < json.length() && (json.charAt(index) == '+' || json.charAt(index) == '-')) {
                    index++;
                }
                while (index < json.length() && Character.isDigit(json.charAt(index))) {
                    index++;
                }
            }
            String number = json.substring(start, index);
            try {
                if (number.contains(".") || number.contains("e") || number.contains("E")) {
                    return Double.parseDouble(number);
                }
                return Long.parseLong(number);
            } catch (NumberFormatException e) {
                throw error("无效 JSON 数字: " + number);
            }
        }

        private void expect(String expected) throws IOException {
            if (!json.startsWith(expected, index)) {
                throw error("期望: " + expected);
            }
            index += expected.length();
        }

        private void expect(char expected) throws IOException {
            if (index >= json.length() || json.charAt(index) != expected) {
                throw error("期望字符: " + expected);
            }
            index++;
        }

        private boolean peek(char expected) {
            return index < json.length() && json.charAt(index) == expected;
        }

        private void skipWhitespace() {
            while (index < json.length() && Character.isWhitespace(json.charAt(index))) {
                index++;
            }
        }

        private IOException error(String message) {
            return new IOException(message + "，位置 " + index);
        }
    }
}
