package org.mcmodule.drpatch.patchsrc;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Objects;
import java.util.function.BiConsumer;

import org.apache.commons.compress.archivers.sevenz.SevenZArchiveEntry;
import org.apache.commons.compress.archivers.sevenz.SevenZFile;
import org.mcmodule.drpatch.util.EnumOS;
import org.mcmodule.drpatch.util.EnumPatchType;

public class PatchSourceSevenZip extends PatchSource {
	
	private final SevenZFile zip;

	public PatchSourceSevenZip(SevenZFile zip) {
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
		SevenZArchiveEntry entry = getMatchEntry(this.zip.getEntries(), files);
		return entry == null ? null : this.zip.getInputStream(entry);
	}

	@Override
	public void extractPatchFolder(EnumPatchType type, EnumOS os, String sha256, BiConsumer<String, InputStream> consumer) throws IOException {
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
		for (SevenZArchiveEntry entry : getMatchEntries(this.zip.getEntries(), files)) {
			String name = entry.getName();
			if (startsWith == null) {
				int indexOf = startsWithIndexOf(files, name);
				if (indexOf >= 0)
					startsWith = files.get(indexOf);
			}
			if (startsWith != null) {
				assert name.startsWith(startsWith);
				name = name.substring(startsWith.length());
			}
			consumer.accept(name, this.zip.getInputStream(entry));
		}
	}

	private SevenZArchiveEntry getMatchEntry(Iterable<SevenZArchiveEntry> entries, ArrayList<String> files) {
		SevenZArchiveEntry best = null;
		int score = Integer.MAX_VALUE;
		for (SevenZArchiveEntry entry : entries) {
			int indexOf = files.indexOf(entry.getName());
			if (indexOf < 0 || indexOf >= score || !entry.hasStream())
				continue;
			score = indexOf;
			best = entry;
		}
		return best;
	}
	
	private ArrayList<SevenZArchiveEntry> getMatchEntries(Iterable<SevenZArchiveEntry> entries, ArrayList<String> files) {
		ArrayList<SevenZArchiveEntry> best = new ArrayList<>();
		int score = Integer.MAX_VALUE;
		for (SevenZArchiveEntry entry : entries) {
			int indexOf = startsWithIndexOf(files, entry.getName());
			if (indexOf < 0 || indexOf > score || !entry.hasStream())
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
		for (int i = 0, len = files.size(); i < len; i++) {
			if (name.startsWith(files.get(i)))
				return i;
		}
		return -1;
	}

	@Override
	public void close() throws Exception {
		this.zip.close();
	}

}
