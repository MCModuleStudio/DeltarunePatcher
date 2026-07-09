package org.mcmodule.drpatch;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileChannel.MapMode;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import org.apache.commons.io.IOUtils;
import org.mcmodule.drpatch.patchsrc.PatchSource;
import org.mcmodule.drpatch.util.EnumOS;
import org.mcmodule.drpatch.util.EnumPatchType;
import org.mcmodule.util.downloader.Unmapper;

import net.dongliu.jvcdiff.vcdiff.VcdiffDecoder;
import net.dongliu.jvcdiff.vcdiff.exception.PatchException;
import net.dongliu.jvcdiff.vcdiff.io.ByteBufferSeekableStream;
import net.dongliu.jvcdiff.vcdiff.io.FileSeekableStream;

public class DeltarunePatcher {
	
	private final File gameDir, backDir;
	private String backSuffix;

	public DeltarunePatcher(File gameDir) {
		this(gameDir, new File(gameDir, "backup"));
	}
	
	public DeltarunePatcher(File gameDir, File backDir) {
		this(gameDir, backDir, (gameDir.equals(backDir) ? ".bak" : ""));
	}
	
	public DeltarunePatcher(File gameDir, File backDir, String backSuffix) {
		this.gameDir = gameDir;
		this.backDir = backDir;
		this.backSuffix = backSuffix;
	}

	public boolean patch(PatchSource source, EnumPatchType type, EnumOS os) throws IOException, PatchException {
		File workDir = getWorkDir(type, os);
		File backDir = getBackDir(type, os);
		if (!workDir.exists())
			return false;
		File wadFile = new File(workDir, os.getWadFile());
		File outFile = new File(workDir, os.getWadFile() + ".patch");
		File bakFile = new File(backDir, os.getWadFile() + this.backSuffix);
		String sha256 = null;
		boolean patched = false;
		if (wadFile.exists()) {
			try {
				try (FileChannel wadFileChannel = FileChannel.open(wadFile.toPath(), StandardOpenOption.READ)){
					ByteBuffer buffer = wadFileChannel.map(MapMode.READ_ONLY, 0, wadFileChannel.size());
					try (InputStream patchStream = source.getPatchStream(type, os, sha256 = sha256(buffer)); FileSeekableStream outputStream = new FileSeekableStream(new RandomAccessFile(outFile, "rw"))){
						if (patchStream != null) {
							new VcdiffDecoder(new ByteBufferSeekableStream(buffer), new BufferedInputStream(patchStream), outputStream).decode();
							patched = true;
						}
					} finally {
						Unmapper.unmap(buffer);
					}
				}
				if (patched) {
					File parent = bakFile.getParentFile();
					if (parent != null)
						parent.mkdirs();
					Files.move(wadFile.toPath(), bakFile.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
					Files.move(outFile.toPath(), wadFile.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
				}
			} finally {
				if (outFile.exists())
					outFile.delete();
			}
		}
		AtomicBoolean isPatched = new AtomicBoolean(patched);
		source.extractPatchFolder(type, os, sha256, (name, stream) -> {
			try (InputStream in = stream) {
				File outputFile = new File(workDir, name);
				File backupFile = new File(backDir, name + ".bak");
				File parent = outputFile.getParentFile();
				if (parent != null)
					parent.mkdirs();
				if (outputFile.exists()) {
					parent = backupFile.getParentFile();
					if (parent != null)
						parent.mkdirs();
					Files.move(outputFile.toPath(), backupFile.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
				}
				try (FileOutputStream out = new FileOutputStream(outputFile)) {
					IOUtils.copy(in, out);
				}
				isPatched.set(true);
			} catch (IOException e) {
				throw new RuntimeException(e);
			}
		});
		return isPatched.get();
	}

	public boolean restore(EnumPatchType type, EnumOS os) throws IOException {
		File workDir = getWorkDir(type, os);
		File backDir = getBackDir(type, os);
		if (!backDir.exists())
			return false;
		AtomicInteger restored = new AtomicInteger();
		File wadFile = new File(workDir, os.getWadFile());
		File bakFile = new File(backDir, os.getWadFile() + this.backSuffix);
		if (bakFile.exists()) {
			File parent = wadFile.getParentFile();
			if (parent != null)
				parent.mkdirs();
			Files.move(bakFile.toPath(), wadFile.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
			restored.incrementAndGet();
		}
		try (Stream<java.nio.file.Path> stream = Files.walk(backDir.toPath())) {
			stream.sorted(Comparator.reverseOrder()).forEach(path -> {
				try {
					File file = path.toFile();
					if (file.isDirectory()) {
						if (!file.equals(backDir))
							file.delete();
						return;
					}
					String name = file.getName();
					if (!name.endsWith(".bak"))
						return;
					java.nio.file.Path relative = backDir.toPath().relativize(path);
					String relativeName = relative.toString();
					relativeName = relativeName.substring(0, relativeName.length() - 4);
					File outputFile = new File(workDir, relativeName);
					File parent = outputFile.getParentFile();
					if (parent != null)
						parent.mkdirs();
					Files.move(path, outputFile.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
					restored.incrementAndGet();
				} catch (IOException e) {
					throw new RuntimeException(e);
				}
			});
		} catch (RuntimeException e) {
			if (e.getCause() instanceof IOException)
				throw (IOException) e.getCause();
			throw e;
		}
		return restored.get() > 0;
	}

	private File getWorkDir(EnumPatchType type, EnumOS os) {
		return new File(this.gameDir, type == EnumPatchType.CHAPTER_SELECT ? type.getPatchFolder() : (type.getPatchFolder() + "_" + os.getSuffix()));
	}

	private File getBackDir(EnumPatchType type, EnumOS os) {
		return new File(this.backDir, type == EnumPatchType.CHAPTER_SELECT ? type.getPatchFolder() : (type.getPatchFolder() + "_" + os.getSuffix()));
	}

	private String sha256(ByteBuffer buffer) {
		try {
			MessageDigest md = MessageDigest.getInstance("SHA-256");
			md.update(buffer.asReadOnlyBuffer());
			byte[] digest = md.digest();
			char[] digestBuffer = new char[digest.length * 2];
			for (int i = 0, j = 0; i < digest.length; i++) {
				digestBuffer[j++] = "0123456789abcdef".charAt((digest[i] & 0xF0) >> 4);
				digestBuffer[j++] = "0123456789abcdef".charAt((digest[i] & 0x0F));
			}
			return new String(digestBuffer);
		} catch (NoSuchAlgorithmException e) {
			throw new RuntimeException(e);
		}
	}
	
}
