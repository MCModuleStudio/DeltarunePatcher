package org.mcmodule.drpatch.util;

import java.util.Comparator;
import java.util.Locale;

public class ContainsComparator implements Comparator<String> {

	private final String keyword;

	public ContainsComparator(String keyword) {
		this.keyword = keyword.toLowerCase(Locale.ROOT);
	}

	@Override
	public int compare(String a, String b) {
		String keyword = this.keyword;
		boolean ac = a.toLowerCase(Locale.ROOT).contains(keyword);
		boolean bc = b.toLowerCase(Locale.ROOT).contains(keyword);

		if (ac == bc) {
			return 0;
		}

		return ac ? -1 : 1;
	}
}