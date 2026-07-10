package org.mcmodule.drpatch.ui;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

interface PatchDownloadSource {
    String name();

    List<Release> listReleases() throws IOException, InterruptedException;

    Path download(Asset asset, Path target, ProgressCallback progressCallback) throws IOException, InterruptedException;

    String toString();

    interface ProgressCallback {
        void update(DownloadProgress progress);
    }

    class DownloadProgress {
        private final int percent;
        private final long bytesPerSecond;

        DownloadProgress(int percent, long bytesPerSecond) {
            this.percent = percent;
            this.bytesPerSecond = bytesPerSecond;
        }

        int percent() {
            return percent;
        }

        long bytesPerSecond() {
            return bytesPerSecond;
        }
    }

    class Release {
        private final String name;
        private final String version;
        private final String publishedAt;
        private final List<Asset> assets;

        Release(String name, String version, String publishedAt, List<Asset> assets) {
            this.name = name;
            this.version = version;
            this.publishedAt = publishedAt;
            ArrayList<Asset> list = new ArrayList<>(assets);
            list.sort(Comparator.nullsLast(Comparator.comparing(Asset::name, PatchInstallerUI.VERSION_COMPARATOR)));
            this.assets = Collections.unmodifiableList(list);
        }

        String name() {
            return name;
        }

        String version() {
            return version;
        }

        List<Asset> assets() {
            return assets;
        }

        @Override
        public String toString() {
            String date = publishedAt == null || publishedAt.length() < 10 ? "" : " - " + publishedAt.substring(0, 10);
            return name + " (" + version + ")" + date;
        }
    }

    class Asset {
        private final String name;
        private final String location;
        private final long size;

        Asset(String name, String location, long size) {
            this.name = name;
            this.location = location;
            this.size = size;
        }

        String name() {
            return name;
        }

        String location() {
            return location;
        }

        long size() {
            return size;
        }

        @Override
        public String toString() {
            if (size <= 0) {
                return name;
            }
            return name + " (" + formatSize(size) + ")";
        }

        private static String formatSize(long bytes) {
            double value = bytes;
            String[] units = { "B", "KB", "MB", "GB" };
            int unitIndex = 0;
            while (value >= 1024 && unitIndex < units.length - 1) {
                value /= 1024;
                unitIndex++;
            }
            if (unitIndex == 0) {
                return bytes + " " + units[unitIndex];
            }
            return String.format("%.1f %s", value, units[unitIndex]);
        }
    }
}
