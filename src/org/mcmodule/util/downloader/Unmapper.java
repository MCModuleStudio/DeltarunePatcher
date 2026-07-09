package org.mcmodule.util.downloader;

import java.lang.reflect.Field;
import java.nio.ByteBuffer;

import sun.misc.Unsafe;

public final class Unmapper {

	private static final Unsafe unsafe;
//	private static final Method invokeCleaner;
//
	static {
		Object u = null;
//		Method m = null;

		// 尝试 JDK9+ 的 Unsafe.invokeCleaner
		try {
			Class<?> unsafeClass = Class.forName("sun.misc.Unsafe");
			Field f = unsafeClass.getDeclaredField("theUnsafe");
			f.setAccessible(true);
			u = f.get(null);
//			m = unsafeClass.getMethod("invokeCleaner", ByteBuffer.class);
		} catch (Throwable ignored) {
			// 忽略，降级走 Cleaner
		}

		unsafe = (Unsafe) u;
//		invokeCleaner = m;
	}

	public static void unmap(ByteBuffer buffer) {
		if (buffer == null || !buffer.isDirect()) {
			return;
		}
		
		unsafe.invokeCleaner(buffer);

//		// ✅ 优先：JDK9+ Unsafe.invokeCleaner
//		if (unsafe != null && invokeCleaner != null) {
//			try {
//				invokeCleaner.invoke(unsafe, buffer);
//				return;
//			} catch (Throwable ignored) {
//				// 失败则 fallback
//			}
//		}
//
//		// ✅ fallback：JDK8 Cleaner 反射
//		try {
//			Method cleanerMethod = buffer.getClass().getMethod("cleaner");
//			cleanerMethod.setAccessible(true);
//			Object cleaner = cleanerMethod.invoke(buffer);
//
//			if (cleaner != null) {
//				Method clean = cleaner.getClass().getMethod("clean");
//				clean.invoke(cleaner);
//			}
//		} catch (Throwable ignored) {
//			// 最终放弃（交给 GC）
//		}
	}

	private Unmapper() {
	}
}