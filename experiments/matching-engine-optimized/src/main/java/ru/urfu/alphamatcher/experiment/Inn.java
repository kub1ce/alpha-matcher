package ru.urfu.alphamatcher.experiment;

import java.util.regex.Pattern;

/** Syntax and checksum only; this is not verification against a tax authority. */
public final class Inn {
    private static final Pattern FORMAT = Pattern.compile("[0-9]{10}|[0-9]{12}");
    private static final int[] LEGAL = {2, 4, 10, 3, 5, 9, 4, 6, 8};
    private static final int[] FIRST = {7, 2, 4, 10, 3, 5, 9, 4, 6, 8};
    private static final int[] SECOND = {3, 7, 2, 4, 10, 3, 5, 9, 4, 6, 8};

    private Inn() { }

    public static boolean valid(String value) {
        if (value == null || !FORMAT.matcher(value).matches() || value.chars().allMatch(c -> c == '0'))
            return false;
        return value.length() == 10 ? digit(value, LEGAL) == value.charAt(9) - '0'
                : digit(value, FIRST) == value.charAt(10) - '0'
                && digit(value, SECOND) == value.charAt(11) - '0';
    }

    private static int digit(String value, int[] weights) {
        int sum = 0;
        for (int i = 0; i < weights.length; i++) sum += (value.charAt(i) - '0') * weights[i];
        return sum % 11 % 10;
    }
}
