package org.mcmodule.drpatch.patchsrc;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.function.BiConsumer;

import org.apache.commons.compress.archivers.sevenz.SevenZFile;
import org.mcmodule.drpatch.util.EnumOS;
import org.mcmodule.drpatch.util.EnumPatchType;

public abstract class PatchSource implements AutoCloseable {
	
	public static final String[] PATCH_SUFFIX = { ".xdelta", ".vcdiff" };
	
	public abstract InputStream getPatchStream(EnumPatchType type, EnumOS os, String sha256) throws IOException;
	
	public abstract void extractPatchFolder(EnumPatchType type, EnumOS os, String sha256, BiConsumer<String, InputStream> consumer) throws IOException;

	public static PatchSource from(File file) throws IOException {
		String name = file.getName();
		if (name.endsWith("7z")) {
			return new PatchSourceSevenZip(SevenZFile.builder().setFile(file).get());
		}
		return null;
	}
	
}
