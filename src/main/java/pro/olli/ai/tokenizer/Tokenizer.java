/*
 * Copyright (c) 2025 Kai Laurinolli. All rights reserved.
 */

package pro.olli.ai.tokenizer;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Interface for tokenizers.
 */
public interface Tokenizer {

    /**
     * Tokenize text using a simple regex-based tokenizer. This is a simplified approximation, not a true BPE
     * tokenizer.
     *
     * @param text Text to tokenize
     * @return List of string tokens
     * @deprecated This method uses a simplified tokenization approach and returns String tokens instead of Integer IDs.
     * For BPE tokenization returning Integer token IDs, use {@link BytePairEncodingTokenizer#tokenize(String)} instead.
     */
    @Deprecated
    static List<String> tokenize(String text) {
        return simpleTokenize(text);
    }
    
    /**
     * Non-deprecated replacement for tokenize() that uses the same implementation.
     * This simple tokenizer splits text into tokens based on a regex pattern.
     *
     * @param text Text to tokenize
     * @return List of string tokens
     */
    static List<String> simpleTokenize(String text) {
        // Simple regex tokenization as fallback
        if (text == null || text.isEmpty()) {
            return new ArrayList<>();
        }

        // GPT tokenization regex pattern - simplified approximation
        Pattern GPT_TOKEN_PATTERN =
            Pattern.compile("'s|'t|'re|'ve|'m|'ll|'d|[\\p{L}\\p{N}]+|[^\\s]");

        List<String> tokens = new ArrayList<>();
        Matcher matcher = GPT_TOKEN_PATTERN.matcher(text);

        while (matcher.find()) {
            tokens.add(matcher.group());
        }

        return tokens;
    }
}
