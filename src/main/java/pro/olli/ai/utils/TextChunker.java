/*
 * Copyright (c) 2025 Kai Laurinolli. All rights reserved.
 */
package pro.olli.ai.utils;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.jboss.logging.Logger;

/**
 * Utility for chunking text documents into smaller pieces for embedding generation.
 * Provides different chunking strategies for different types of text.
 */
public class TextChunker {
    
    private static final Logger LOG = Logger.getLogger(TextChunker.class);
    
    // Default chunk sizes
    public static final int DEFAULT_CHUNK_SIZE = 500;
    public static final int DEFAULT_CHUNK_OVERLAP = 100;
    
    // Sentence boundary regex pattern
    private static final Pattern SENTENCE_BOUNDARY = 
        Pattern.compile("(?<=[.!?])\\s+(?=[A-Z])");
    
    // Paragraph boundary regex pattern
    private static final Pattern PARAGRAPH_BOUNDARY = 
        Pattern.compile("\\n\\s*\\n");
    
    /**
     * Available chunking strategies
     */
    public enum ChunkingStrategy {
        FIXED_SIZE,      // Chunk by exact character count
        SENTENCE,        // Chunk by sentence boundaries
        PARAGRAPH,       // Chunk by paragraph boundaries
        SEMANTIC         // Chunk by semantic boundaries (requires additional logic)
    }
    
    /**
     * Chunk text using the specified strategy.
     * 
     * @param text The text to chunk
     * @param strategy The chunking strategy to use
     * @param chunkSize Maximum size of each chunk (characters)
     * @param chunkOverlap Overlap between chunks (characters)
     * @return List of text chunks
     */
    public static List<String> chunkText(String text, ChunkingStrategy strategy, 
                                        int chunkSize, int chunkOverlap) {
        if (text == null || text.trim().isEmpty()) {
            return new ArrayList<>();
        }
        
        // Apply different chunking strategies
        return switch(strategy) {
            case FIXED_SIZE -> chunkByFixedSize(text, chunkSize, chunkOverlap);
            case SENTENCE -> chunkBySentence(text, chunkSize, chunkOverlap);
            case PARAGRAPH -> chunkByParagraph(text, chunkSize, chunkOverlap);
            case SEMANTIC -> chunkBySemantic(text, chunkSize, chunkOverlap);
        };
    }
    
    /**
     * Convenience method to chunk text with default settings
     */
    public static List<String> chunkText(String text) {
        return chunkText(text, ChunkingStrategy.SENTENCE, 
                        DEFAULT_CHUNK_SIZE, DEFAULT_CHUNK_OVERLAP);
    }
    
    /**
     * Chunks text by fixed character count
     */
    private static List<String> chunkByFixedSize(String text, int chunkSize, int chunkOverlap) {
        List<String> chunks = new ArrayList<>();
        
        if (chunkSize <= 0) {
            LOG.warn("Invalid chunk size, using default");
            chunkSize = DEFAULT_CHUNK_SIZE;
        }
        
        if (chunkOverlap >= chunkSize) {
            LOG.warn("Overlap larger than chunk size, adjusting");
            chunkOverlap = chunkSize / 5; // 20% overlap
        }
        
        int textLength = text.length();
        int startIndex = 0;
        
        while (startIndex < textLength) {
            int endIndex = Math.min(startIndex + chunkSize, textLength);
            chunks.add(text.substring(startIndex, endIndex));
            startIndex += (chunkSize - chunkOverlap);
        }
        
        return chunks;
    }
    
    /**
     * Chunks text by sentence boundaries
     */
    private static List<String> chunkBySentence(String text, int chunkSize, int chunkOverlap) {
        // Split text into sentences
        String[] sentences = SENTENCE_BOUNDARY.split(text);
        return joinUntilSize(sentences, chunkSize, chunkOverlap);
    }
    
    /**
     * Chunks text by paragraph boundaries
     */
    private static List<String> chunkByParagraph(String text, int chunkSize, int chunkOverlap) {
        // Split text into paragraphs
        String[] paragraphs = PARAGRAPH_BOUNDARY.split(text);
        return joinUntilSize(paragraphs, chunkSize, chunkOverlap);
    }
    
    /**
     * Chunks text by semantic boundaries (placeholder implementation)
     * In a real system, this would use more sophisticated NLP to determine semantic units
     */
    private static List<String> chunkBySemantic(String text, int chunkSize, int chunkOverlap) {
        // For now, just use sentence chunking as a placeholder
        // In a real implementation, this would use NLP to identify semantic units
        LOG.info("Semantic chunking not fully implemented, using sentence chunking");
        return chunkBySentence(text, chunkSize, chunkOverlap);
    }
    
    /**
     * Helper method to join text segments until they reach the chunk size
     */
    private static List<String> joinUntilSize(String[] segments, int chunkSize, int chunkOverlap) {
        List<String> chunks = new ArrayList<>();
        
        if (segments.length == 0) {
            return chunks;
        }
        
        StringBuilder currentChunk = new StringBuilder();
        
        for (String segment : segments) {
            // Clean up segment
            segment = segment.trim();
            
            // Skip empty segments
            if (segment.isEmpty()) {
                continue;
            }
            
            // If adding this segment would exceed the chunk size, store the current chunk
            // and start a new one (with overlap)
            if (currentChunk.length() + segment.length() + 1 > chunkSize && currentChunk.length() > 0) {
                // Store current chunk
                chunks.add(currentChunk.toString());
                
                // Create new chunk with overlap from the previous chunk
                if (chunkOverlap > 0 && currentChunk.length() > chunkOverlap) {
                    int startPos = Math.max(0, currentChunk.length() - chunkOverlap);
                    currentChunk = new StringBuilder(currentChunk.substring(startPos));
                } else {
                    currentChunk = new StringBuilder();
                }
            }
            
            // Add a space before appending if the current chunk is not empty
            if (currentChunk.length() > 0) {
                currentChunk.append(" ");
            }
            
            // Add segment to current chunk
            currentChunk.append(segment);
        }
        
        // Add the final chunk if it's not empty
        if (currentChunk.length() > 0) {
            chunks.add(currentChunk.toString());
        }
        
        return chunks;
    }
    
    /**
     * Adds metadata to each chunk to improve relevance
     * 
     * @param chunks List of text chunks
     * @param filename Original filename
     * @param title Document title (optional)
     * @return List of chunks with metadata prefixes
     */
    public static List<String> addMetadataToChunks(List<String> chunks, String filename, String title) {
        if (chunks == null || chunks.isEmpty()) {
            return new ArrayList<>();
        }
        
        String metadata = String.format("Document: %s\n", filename);
        if (title != null && !title.isEmpty()) {
            metadata += String.format("Title: %s\n", title);
        }
        metadata += "Chunk: %d of %d\n\n";
        
        List<String> enhancedChunks = new ArrayList<>();
        int totalChunks = chunks.size();
        
        for (int i = 0; i < chunks.size(); i++) {
            String formattedMetadata = String.format(metadata, i + 1, totalChunks);
            enhancedChunks.add(formattedMetadata + chunks.get(i));
        }
        
        return enhancedChunks;
    }
}
