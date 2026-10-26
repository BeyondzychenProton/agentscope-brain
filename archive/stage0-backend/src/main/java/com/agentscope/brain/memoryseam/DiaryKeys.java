package com.agentscope.brain.memoryseam;

import java.util.regex.Pattern;

public final class DiaryKeys {

    private static final Pattern DIARY = Pattern.compile("memory/\\d{4}-\\d{2}-\\d{2}\\.md");

    private DiaryKeys() {
    }

    public static boolean isDiaryInput(String key) {
        return key != null && DIARY.matcher(key).matches();
    }

    public static boolean isCandidate(String key) {
        return key != null
                && key.startsWith("candidates/")
                && key.endsWith(".md")
                && key.indexOf('\u001f') < 0
                && !key.contains("..");
    }
}
