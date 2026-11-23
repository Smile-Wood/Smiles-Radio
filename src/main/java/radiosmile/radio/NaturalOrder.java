package radiosmile.radio;

import java.util.Comparator;

/**
 * 自然序比较器：让文件名里的数字按数值比较，而不是按字符比较。
 *
 * <p>例如 {@code 1.ogg, 2.ogg, 10.ogg} 的自然序是 1, 2, 10，
 * 而纯字典序会得到 1, 10, 2。
 */
public final class NaturalOrder implements Comparator<String> {
	public static final NaturalOrder INSTANCE = new NaturalOrder();

	private NaturalOrder() {
	}

	@Override
	public int compare(String left, String right) {
		if (left == null) {
			return right == null ? 0 : -1;
		}
		if (right == null) {
			return 1;
		}
		int i = 0;
		int j = 0;
		while (i < left.length() && j < right.length()) {
			char a = left.charAt(i);
			char b = right.charAt(j);
			if (Character.isDigit(a) && Character.isDigit(b)) {
				// 取两边的完整数字段按数值比较
				int startA = i;
				int startB = j;
				while (i < left.length() && Character.isDigit(left.charAt(i))) {
					i++;
				}
				while (j < right.length() && Character.isDigit(right.charAt(j))) {
					j++;
				}
				String numA = left.substring(startA, i).replaceFirst("^0+(?=\\d)", "");
				String numB = right.substring(startB, j).replaceFirst("^0+(?=\\d)", "");
				int cmp = Integer.compare(numA.length(), numB.length());
				if (cmp != 0) {
					return cmp;
				}
				cmp = numA.compareTo(numB);
				if (cmp != 0) {
					return cmp;
				}
				continue;
			}
			if (a != b) {
				return Character.compare(a, b);
			}
			i++;
			j++;
		}
		return Integer.compare(left.length() - i, right.length() - j);
	}
}
