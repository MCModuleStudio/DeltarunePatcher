package org.mcmodule.drpatch.patchsrc;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Objects;
import java.util.function.BiConsumer;
import java.util.stream.Stream;

import org.mcmodule.drpatch.util.EnumOS;
import org.mcmodule.drpatch.util.EnumPatchType;

public class PatchSourceFileSystem extends PatchSource {

	private final Path root;

	public PatchSourceFileSystem(Path root) {
		this.root = Objects.requireNonNull(root);
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

		for (String file : files) {
			Path path = root.resolve(file);
			if (Files.isRegularFile(path)) {
				return Files.newInputStream(path);
			}
		}

		return null;
	}

	@Override
	public void extractPatchFolder(EnumPatchType type, EnumOS os, String sha256,
			BiConsumer<String, InputStream> consumer) throws IOException {

		ArrayList<String> folders = new ArrayList<>();

		String patchFileName = type.getPatchFileName();
		String suffixOS = os.getSuffix();

		if (sha256 != null && sha256.length() > 8) {
			sha256 = sha256.substring(0, 8);
		}

		if (sha256 != null) {
			folders.add(patchFileName + "_" + suffixOS + "_" + sha256);
			folders.add(patchFileName + "_" + sha256);
		}

		folders.add(patchFileName + "_" + suffixOS);
		folders.add(patchFileName);

		Path selected = null;

		for (String folder : folders) {
			Path dir = root.resolve(folder);
			if (Files.isDirectory(dir)) {
				selected = dir;
				break;
			}
		}

		if (selected == null) {
			return;
		}
		Path selected2 = selected;

		try (Stream<Path> stream = Files.walk(selected)) {
			stream.filter(Files::isRegularFile).forEach(path -> {
				try {
					String relative = selected2.relativize(path).toString().replace('\\', '/');
					consumer.accept(relative, Files.newInputStream(path));
				} catch (IOException e) {
					throw new RuntimeException(e);
				}
			});
		} catch (RuntimeException e) {
			if (e.getCause() instanceof IOException io) {
				throw io;
			}
			throw e;
		}
	}

	@Override
	public void close() {
		// NO-OP
	}
}