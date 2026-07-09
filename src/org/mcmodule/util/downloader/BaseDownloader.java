package org.mcmodule.util.downloader;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.io.UnsupportedEncodingException;
import java.lang.reflect.Array;
import java.net.URL;
import java.net.URLDecoder;
import java.nio.ByteBuffer;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileChannel.MapMode;
import java.util.BitSet;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.LongAdder;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock.ReadLock;
import java.util.concurrent.locks.ReentrantReadWriteLock.WriteLock;

public abstract class BaseDownloader<T extends BaseDownloader.WorkerContext> {

	private static final int REGION_SIZE = 1024 * 1024 * 1024/* 1 GiB */; 
	
	protected File file;
	protected URL url;
	protected int numConnections;
	protected URL[] backupURLs;
	
	protected FileInfo fileInfo;
	protected RandomAccessFile randomAccessFile;

	protected MappedByteBuffer[] regions;
	protected BitSet[] progress;

	protected long size;
	protected int blockSize;
	protected long blocks;
	protected int maxBitsPerRegion;
	protected T[] threadContexts;
	protected volatile IOException failureCause;

	private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
	private int assignRegionCount;
	private long blocksPerThread;
	private int numWorkingThread = 0;

	public BaseDownloader(File file, URL url, int numConnections, URL...backupURLs) throws IOException {
		FileInfo fileInfo = this.fileInfo = this.retrieveFileInfo(url);
		if (file.isDirectory()) {
			String name = fileInfo.name;
			if (name == null || name.isEmpty()) {
				String path = url.getPath();
				int lastIndexOf = path.lastIndexOf('/');
				if (lastIndexOf >= 0)
					path = path.substring(lastIndexOf + 1);
				try {
					name = URLDecoder.decode(path, "UTF-8");
				} catch (UnsupportedEncodingException e) {
					assert false;
					throw new InternalError("Bad jvm implementation");
				}
			}
			if (name == null || name.isEmpty()) {
				name = "download";
			}
			file = new File(file, name);
		}
		this.file = file;
		this.url = url;
		this.numConnections = numConnections;
		this.backupURLs = backupURLs;
		long size = this.size = fileInfo.size;
		long regionsLong = Long.divideUnsigned((size + REGION_SIZE - 1L), REGION_SIZE);
		if (regionsLong < 0 || regionsLong > (Integer.MAX_VALUE - 8))
			throw new IOException("File too big");
		int blockSize = this.blockSize = getBlockSize(size);
		long blocks = this.blocks = Long.divideUnsigned((size + blockSize - 1L), blockSize);
		BitSet[] progress = this.progress = new BitSet[(int) regionsLong];
		RandomAccessFile randomAccessFile = this.randomAccessFile = new RandomAccessFile(file, "rw");
		numConnections = this.numConnections = (int) Math.min(numConnections, Long.divideUnsigned(size + blockSize - 1L, blockSize));
		this.blocksPerThread = blocks / numConnections;
		this.maxBitsPerRegion = (REGION_SIZE + blockSize - 1) / blockSize;
		boolean exceptionOccurred = true;
		try {
			randomAccessFile.setLength(size);
			MappedByteBuffer[] regions = this.regions = new MappedByteBuffer[(int) regionsLong];
			FileChannel channel = randomAccessFile.getChannel();
			MapMode mapMode = MapMode.READ_WRITE;
			int i = 0;
			for (long pos = 0L; pos < size; pos += REGION_SIZE, i++) {
				int regionSize = (int) Math.min(size - pos, REGION_SIZE);
				progress[i] = new BitSet((regionSize + blockSize - 1) / blockSize);
				regions[i] = channel.map(mapMode, pos, regionSize);
			}
			exceptionOccurred = false;
		} finally {
			if (exceptionOccurred) {
				randomAccessFile.close();
			}
		}
	}
	
	private int getBlockSize(long size) {
		int bs = 1 << 16/* 16 KiB */;
		for (int i = 0; size >= (1 << 28L/* 256 MiB */) && i < 8; i++) {
			bs <<= 1;
			size >>= 1L;
		}
		return bs;
	}

	public void start() {
		int numConnections = this.numConnections;
		for (int i = 0; i < numConnections; i++) {
			new Thread(this::run, "Download Worker Thread#" + i).start();
		}
	}
	
	private void run() {
		T ctx = createContext();
		int blockSize = this.blockSize;
		byte[] block = new byte[blockSize];
		WriteLock writeLock = this.lock.writeLock();
		writeLock.lock();
		this.numWorkingThread++;
		writeLock.unlock();
		while (failureCause == null && assignRegion(ctx)) {
			InputStream is = null;
			try {
				is = openConnection(ctx);
				int size = (int) Math.min(Math.min(this.size, ctx.to) - ctx.from, blockSize);
				int reads, off = 0, rem = size;
				do {
					reads = is.read(block, off, rem);
					if (reads >= 0) {
						ctx.downloadSpeed.add(reads);
						off += reads;
						rem -= reads;
						if (rem == 0) {
							boolean overlap = writeRegion(ctx, block, off);
							size = (int) Math.min(Math.min(this.size, ctx.to) - ctx.from, blockSize);
							off = 0;
							rem = size;
							if (overlap)
								break;
						}
					}
				} while (reads >= 0 && size > 0);
				if (reads < 0 && size > 0) {
					if (off > 0) {
						writeRegion(ctx, block, off);
					}
					throw new IOException("Connection closed before range completed");
				}
				if (off > 0) {
					writeRegion(ctx, block, off);
				}
				ctx.failures = 0;
			} catch (IOException e) {
				if (++ctx.failures >= getMaxRetries()) {
					failureCause = e;
				} else {
					e.printStackTrace();
				}
			} finally {
				if (is != null) {
					closeConnection(ctx, is);
				}
			}
		}
		writeLock.lock();
		int numWorkingThread = --this.numWorkingThread;
		writeLock.unlock();
		if (numWorkingThread == 0) {
			MappedByteBuffer[] regions = this.regions;
			for (int i = 0, len = regions.length; i < len; i++) {
				MappedByteBuffer region = regions[i];
				region.force();
				Unmapper.unmap(region);
			}
			this.regions = null;
			try {
				this.randomAccessFile.close();
			} catch (IOException e) {
			}
			System.gc();
			Runtime.getRuntime().runFinalization();
			if (this.fileInfo.lastModified != -1) {
				this.file.setLastModified(this.fileInfo.lastModified);
			}
			downloadCompleted();
		}
	}

	protected boolean writeRegion(T ctx, byte[] block, int reads) {
		long from = ctx.from;
		long to = Math.min(from + reads, this.size);
		int blockSize = this.blockSize;
		assert from == from / blockSize * blockSize; // assume we are aligned
		MappedByteBuffer[] regions = this.regions;
		BitSet[] progress = this.progress;
		WriteLock writeLock = this.lock.writeLock();
		boolean overlap = false;
		while (from < to) {
			int i = (int) Long.divideUnsigned(from, REGION_SIZE);
			ByteBuffer region = regions[i].slice();
			int off = (int) (from - (i * REGION_SIZE));
			int len = (int) Math.min(to - from, region.capacity() - off);
			region.position(off);
			region.put(block, 0, len);
			writeLock.lock();
			try {
				while (len > 0) {
					int idx = off / blockSize;
					BitSet bitSet = progress[i];
					overlap |= bitSet.get(idx);
					int blockOffset = off % blockSize;
					int blockRemain = blockSize - blockOffset;
					int inc = Math.min(len, blockRemain);
					len -= inc;
					off += inc;
					from += inc;
					if (inc == blockRemain || from >= this.size) {
						bitSet.set(idx);
					}
				}
			} finally {
				writeLock.unlock();
			}
		}
		ctx.from = from;
		return overlap;
	}
	
	@SuppressWarnings("unchecked")
	protected boolean assignRegion(T ctx) {
		if (failureCause != null) {
			return false;
		}
		int blockSize = this.blockSize;
		URL[] backupURLs = this.backupURLs;
		URL url;
		if (backupURLs != null && backupURLs.length > 0) {
			int length = backupURLs.length;
			int nextInt = ThreadLocalRandom.current().nextInt(length + 1);
			if (nextInt == length) {
				url = this.url;
			} else {
				url = backupURLs[nextInt];
			}
		} else {
			url = this.url;
		}
		ctx.url = url;
		WriteLock writeLock = this.lock.writeLock();
		writeLock.lock();
		try {
			// Assign initial regions
			if (this.assignRegionCount < this.numConnections) {
				long blocksPerThread = this.blocksPerThread;
				if (this.threadContexts == null) {
					this.threadContexts = (T[]) Array.newInstance(ctx.getClass(), this.numConnections);
				}
				this.threadContexts[this.assignRegionCount] = ctx;
				ctx.from = blockSize * blocksPerThread * this.assignRegionCount++;
				ctx.to   = blockSize * blocksPerThread * this.assignRegionCount;
				return true;
			}
			// If they finished its regions, find a biggest region to assign
			// NOTE: We don't care region overlaps here, it handles at downloading
			Range range = findMaxZeroRange();
			if (range != null) {
				long len = range.to - range.from;
				if (len >= 1 << 24/* 16 MiB */) {
					range.from += len >> 1L;
				}
				ctx.from = range.from / blockSize * blockSize; // Alignment to block size
				ctx.to   = range.to;
				return true;
			}
		} finally {
			writeLock.unlock();
		}
		return false;
	}
	
	protected Range findMaxZeroRange() {
		BitSet[] progress = this.progress;
		int blockSize = this.blockSize;

		long bestStart = -1;
		long bestLen = 0;

		long currentStart = -1;
		long currentLen = 0;

		long globalOffsetBlocks = 0;

		for (int r = 0, l = progress.length; r < l; r++) {
			BitSet bs = progress[r];
			int size = this.maxBitsPerRegion;
			if (r == l - 1)
				size = (int) Long.divideUnsigned((this.size - ((long) r * REGION_SIZE)) + blockSize - 1L, blockSize);

			int bit = 0;
			while (bit < size) {
				int zeroStart = bs.nextClearBit(bit);

				if (zeroStart >= size)
					break;

				int nextOne = bs.nextSetBit(zeroStart);
				int zeroEnd = (nextOne == -1 || nextOne > size) ? size : nextOne;

				int len = zeroEnd - zeroStart;

				if (currentStart == -1) {
					currentStart = globalOffsetBlocks + zeroStart;
					currentLen = len;
				} else {
					// 判断是否连续
					if (currentStart + currentLen == globalOffsetBlocks + zeroStart) {
						currentLen += len;
					} else {
						// 断开，比较最大值
						if (currentLen > bestLen) {
							bestLen = currentLen;
							bestStart = currentStart;
						}
						currentStart = globalOffsetBlocks + zeroStart;
						currentLen = len;
					}
				}

				bit = zeroEnd;
			}

			// 如果整个 region 都是 0，需要特殊处理
//			if (bs.isEmpty()) {
//				int regionBlocks = size;
//
//				if (currentStart == -1) {
//					currentStart = globalOffsetBlocks;
//					currentLen = regionBlocks;
//				} else {
//					currentLen += regionBlocks;
//				}
//			}

			globalOffsetBlocks += size;
		}

		// 最后一段
		if (currentLen > bestLen) {
			bestLen = currentLen;
			bestStart = currentStart;
		}

		if (bestLen <= 0)
			return null;

		long from = bestStart * blockSize;
		long to = from + bestLen * blockSize;

		return new Range(from, to);
	}

	protected int getMaxRetries() {
		return 5;
	}

	protected abstract T createContext();

	protected abstract FileInfo retrieveFileInfo(URL url) throws IOException;
	
	protected abstract InputStream openConnection(T ctx) throws IOException;
	
	protected abstract void closeConnection(T ctx, InputStream is);
	
	protected void downloadCompleted() {}
	
	public boolean isFailed() {
		return failureCause != null;
	}

	public IOException getFailureCause() {
		return failureCause;
	}

	public boolean isCompleted() {
		ReadLock readLock = this.lock.readLock();
		readLock.lock();
		try {
			BitSet[] progress = this.progress;
			int blockSize = this.blockSize;

			for (int r = 0, l = progress.length; r < l; r++) {
				BitSet bs = progress[r];
				int size = this.maxBitsPerRegion;
				if (r == l - 1)
					size = (int) Long.divideUnsigned((this.size - ((long) r * REGION_SIZE)) + blockSize - 1L, blockSize);
				if (bs.nextClearBit(0) != size)
					return false;
			}
		} finally {
			readLock.unlock();
		}
		return true;
	}


	public static class FileInfo {
		public String name;
		public long size;
		public long lastModified = -1;
	}
	
	public static class WorkerContext {
		public URL url;
		public long from, to;
		public LongAdder downloadSpeed = new LongAdder();
		int failures;
	}
	
	public static class Range {
		public long from;
		public long to;

		public Range(long from, long to) {
			this.from = from;
			this.to = to;
		}
	}
}
