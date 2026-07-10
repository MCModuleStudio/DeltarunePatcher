package org.mcmodule.drpatch.patchsrc;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.Objects;
import java.util.function.BiConsumer;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import org.mcmodule.drpatch.util.EnumOS;
import org.mcmodule.drpatch.util.EnumPatchType;

public class PatchSourceZip extends PatchSource {

	private final ZipFile zip;

	public PatchSourceZip(ZipFile zip) {
		this.zip = Objects.requireNonNull(zip);
	}

	@Override
	public InputStream getPatchStream(EnumPatchType type, EnumOS os, String sha256) throws IOException {
		ArrayList<String> files = new ArrayList<>();
		String patchFileName = type.getPatchFileName();
		String suffixOS = os.getSuffix();

		if (sha256 != null && sha256.length() > 8) {
			sha256 = sha256.substring(0, 8);
		}

		for (String suffix : PatchSource.PATCH_SUFFIX) {
			if (sha256 != null) {
				files.add(patchFileName + "_" + suffixOS + "_" + sha256 + suffix);
				files.add(patchFileName + "_" + sha256 + suffix);
			}
			files.add(patchFileName + "_" + suffixOS + suffix);
			files.add(patchFileName + suffix);
		}

		ZipEntry entry = getMatchEntry(files);
		return entry == null ? null : zip.getInputStream(entry);
	}

	@Override
	public void extractPatchFolder(EnumPatchType type, EnumOS os, String sha256,
			BiConsumer<String, InputStream> consumer) throws IOException {

		ArrayList<String> files = new ArrayList<>();
		String patchFileName = type.getPatchFileName();
		String suffixOS = os.getSuffix();

		if (sha256 != null && sha256.length() > 8) {
			sha256 = sha256.substring(0, 8);
		}

		String suffix = "/";

		if (sha256 != null) {
			files.add(patchFileName + "_" + suffixOS + "_" + sha256 + suffix);
			files.add(patchFileName + "_" + sha256 + suffix);
		}

		files.add(patchFileName + "_" + suffixOS + suffix);
		files.add(patchFileName + suffix);

		String startsWith = null;

		for (ZipEntry entry : getMatchEntries(files)) {
			String name = entry.getName();

			if (startsWith == null) {
				int indexOf = startsWithIndexOf(files, name);
				if (indexOf >= 0)
					startsWith = files.get(indexOf);
			}

			if (startsWith != null) {
				name = name.substring(startsWith.length());
			}

			consumer.accept(name, zip.getInputStream(entry));
		}
	}

	private ZipEntry getMatchEntry(ArrayList<String> files) {
		ZipEntry best = null;
		int score = Integer.MAX_VALUE;

		Enumeration<? extends ZipEntry> entries = zip.entries();
		while (entries.hasMoreElements()) {
			ZipEntry entry = entries.nextElement();

			int indexOf = files.indexOf(entry.getName());
			if (indexOf < 0 || indexOf >= score || entry.isDirectory())
				continue;

			score = indexOf;
			best = entry;
		}

		return best;
	}

	private ArrayList<ZipEntry> getMatchEntries(ArrayList<String> files) {
		ArrayList<ZipEntry> best = new ArrayList<>();
		int score = Integer.MAX_VALUE;

		Enumeration<? extends ZipEntry> entries = zip.entries();
		while (entries.hasMoreElements()) {
			ZipEntry entry = entries.nextElement();

			int indexOf = startsWithIndexOf(files, entry.getName());
			if (indexOf < 0 || indexOf > score || entry.isDirectory())
				continue;

			if (indexOf < score) {
				score = indexOf;
				best.clear();
			}

			best.add(entry);
		}

		return best;
	}

	private int startsWithIndexOf(ArrayList<String> files, String name) {
		for (int i = 0; i < files.size(); i++) {
			if (name.startsWith(files.get(i)))
				return i;
		}
		return -1;
	}

	@Override
	public void close() throws Exception {
		zip.close();
	}

}