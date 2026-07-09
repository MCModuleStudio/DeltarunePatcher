package org.mcmodule.drpatch.util;

import java.util.Locale;

public enum EnumOS {
	WINDOWS("windows", "data.win"),
	MACOS("mac", "game.ios"),
	LINUX("windows", "data.win"); // Linux use windows file

	private final String suffix;
	private final String wadFile;

	EnumOS(String suffix, String wadFile) {
		this.suffix = suffix;
		this.wadFile = wadFile;
	}

	public static EnumOS detect() {
		String os = System.getProperty("os.name").toLowerCase(Locale.ROOT);
		if (os.startsWith("windows")) {
			return EnumOS.WINDOWS;
		}
		if (os.startsWith("mac")) {
			return EnumOS.MACOS;
		}
		if (os.contains("linux") || os.contains("unix")) {
			return EnumOS.LINUX;
		}
		return EnumOS.LINUX;
	}

	public String getSuffix() {
		return this.suffix;
	}

	public String getWadFile() {
		return this.wadFile;
	}
}
