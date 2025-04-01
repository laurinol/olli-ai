/*
 * Copyright (c) 2025 Olli Solutions LLC. All rights reserved.
 */
package pro.olli.ai.tokenizer;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.jboss.logging.Logger;

/**
 * A simple tokenizer for estimating token counts for LLM inputs.
 * This is a simplified implementation, not a full BPE tokenizer.
 * 
 * @deprecated This class is deprecated and will be removed in a future release.
 * Use {@link BytePairEncodingTokenizer} instead, which provides more accurate tokenization
 * with HuggingFace Tokenizers. This class now delegates to BytePairEncodingTokenizer when available.
 */
@Deprecated
public class SimpleTokenizer implements Tokenizer {

    private static final Logger LOG = Logger.getLogger(SimpleTokenizer.class);

    // GPT tokenization regex pattern - simplified approximation
    // Moved to Tokenizer interface for direct access in the static tokenize method
    static final Pattern GPT_TOKEN_PATTERN =
        Pattern.compile("'s|'t|'re|'ve|'m|'ll|'d|[\\p{L}\\p{N}]+|[^\\s]");

    // Average ratio for converting characters to GPT-4 tokens
    private static final double GPT4_CHAR_TO_TOKEN_RATIO = 0.25;

    // Cache common token counts to avoid re-computation
    private static final Map<String, Integer> tokenCountCache = new HashMap<>();

    /**
     * Estimate token count for the given text.
     * Now delegates to BytePairEncodingTokenizer when available.
     *
     * @param text Text to count tokens for
     * @return Estimated token count
     * @deprecated Use {@link BytePairEncodingTokenizer#getTokenCount(String)} instead
     */
    @Deprecated
    public static int estimateTokenCount(String text) {
        try {
            // Try to use BytePairEncodingTokenizer if available
            return BytePairEncodingTokenizer.getTokenCount(text);
        } catch (Exception e) {
            LOG.warn("BytePairEncodingTokenizer not available, falling back to legacy implementation");
            // Fall back to legacy implementation
            if (text == null || text.isEmpty()) {
                return 0;
            }

            // Check cache first
            if (tokenCountCache.containsKey(text)) {
                return tokenCountCache.get(text);
            }

            // For short texts, use regex tokenization
            if (text.length() < 1000) {
                int count = Tokenizer.tokenize(text).size();
                tokenCountCache.put(text, count);
                return count;
            }

            // For longer texts, use character ratio approximation for efficiency
            // This is faster but less accurate
            int estimatedCount = (int)(text.length() * GPT4_CHAR_TO_TOKEN_RATIO);
            tokenCountCache.put(text, estimatedCount);
            return estimatedCount;
        }
    }

    /**
     * Truncate text to fit within a maximum token limit.
     * Now delegates to BytePairEncodingTokenizer when available.
     *
     * @param text Text to truncate
     * @param maxTokens Maximum number of tokens allowed
     * @return Truncated text
     * @deprecated Use {@link BytePairEncodingTokenizer#truncateText(String, int)} instead
     */
    @Deprecated
    public static String truncateToTokenLimit(String text, int maxTokens) {
        try {
            // Try to use BytePairEncodingTokenizer if available
            return BytePairEncodingTokenizer.truncateText(text, maxTokens);
        } catch (Exception e) {
            LOG.warn("BytePairEncodingTokenizer not available, falling back to legacy implementation");
            // Fall back to legacy implementation
            if (text == null || text.isEmpty() || maxTokens <= 0) {
                return "";
            }

            // If already under limit, return as is
            int estimatedTokens = estimateTokenCount(text);
            if (estimatedTokens <= maxTokens) {
                return text;
            }

            // For simple truncation, use character-based approximation
            // This is imprecise but simple
            int targetLength = (int)(maxTokens / GPT4_CHAR_TO_TOKEN_RATIO);
            if (targetLength >= text.length()) {
                return text;
            }

            // Find a good breakpoint (sentence or word boundary)
            int breakpoint = findGoodBreakpoint(text, targetLength);
            return text.substring(0, breakpoint) + "...";
        }
    }

    /**
     * Find a good breakpoint for truncation (sentence or word boundary)
     */
    private static int findGoodBreakpoint(String text, int targetLength) {
        // First try to find a sentence boundary
        int lastSentenceEnd = text.lastIndexOf(". ", targetLength);
        if (lastSentenceEnd > targetLength * 0.8) {
            return lastSentenceEnd + 1; // Include the period
        }

        // If no good sentence boundary, find a word boundary
        int lastSpacePos = text.lastIndexOf(" ", targetLength);
        if (lastSpacePos > 0) {
            return lastSpacePos;
        }

        // If all else fails, just truncate at the target length
        return Math.min(targetLength, text.length());
    }

    /**
     * Clear the token count cache
     */
    public static void clearCache() {
        tokenCountCache.clear();
    }
}