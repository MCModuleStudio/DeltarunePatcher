package org.mcmodule.drpatch.util;

public enum EnumPatchType {
	CHAPTER_SELECT("主程序"),
	CHAPTER_1("第一章"),
	CHAPTER_2("第二章"),
	CHAPTER_3("第三章"),
	CHAPTER_4("第四章"),
	CHAPTER_5("第五章"),
	CHAPTER_6("第六章"),
	CHAPTER_7("第七章");
	
	private final String name;

	EnumPatchType(String name) {
		this.name = name;
	}

	public String getPatchFileName() {
		if (this == CHAPTER_SELECT)
			return "main";
		return "chapter" + this.ordinal();
	}
	
	public String getPatchFolder() {
		if (this == CHAPTER_SELECT)
			return ".";
		return "chapter" + this.ordinal();
	}

	public String getName() {
		return this.name;
	}

	@Override
	public String toString() {
		return getName();
	}
}
