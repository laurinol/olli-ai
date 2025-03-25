# Ollama Integration Guide

This document outlines how to set up and use the Ollama integration with the backend.

## Overview

The backend integrates with Ollama for AI capabilities:
- Text embeddings generation (`EmbeddingService`)
- Chat completions (`QueryService`)

## Integration Method

We use the langchain4j library for Ollama integration:

- Uses the langchain4j library for a clean, robust integration
- Supports streaming responses 
- Provides comprehensive error handling
- Classes: `LangchainEmbeddingService`, `LangchainQueryService`

## Setup Instructions

1. **Install Ollama**
   - Follow instructions at [ollama.ai/download](https://ollama.ai/download)

2. **Start Ollama**
   - Use the provided script: `./test/config/start-ollama.sh`
   - This will start Ollama and download required models

3. **Verify Ollama Status**
   - Use the checker script: `./test/check-ollama.sh`
   - This checks if Ollama is running and properly configured

4. **Configure Application**
   - In `application.properties` or via environment variables:
   ```properties
   ai.provider=ollama
   ollama.endpoint=http://localhost:11434
   ollama.embedding.model=llama3
   ollama.embedding.dimension=4096
   ollama.chat.model=llama3
   ```

## Troubleshooting

### Recent Error Fixes

1. **NullPointerException in ResponseBody**
   ```
   java.lang.NullPointerException: Cannot invoke "okhttp3.ResponseBody.byteStream()" because the return value of "retrofit2.Response.body()" is null
   ```
   - **Cause**: Ollama server not running or not accessible
   - **Solution**: Added pre-check for Ollama server availability
   - **Implementation**: Added health check in `LangchainQueryService` and fallback

2. **Enhanced Logging**
   - Added debug logging to track request/response flow
   - Added service availability checks on startup
   - Created diagnostic tools in the `test` directory

### Common Issues

1. **Ollama Not Running**
   - Run `./test/config/start-ollama.sh` to start Ollama

2. **Model Not Found**
   - Ensure models are downloaded: `ollama pull llama3`
   - Check available models: `ollama list`

3. **Wrong Embedding Dimensions**
   - Different models produce embeddings of different dimensions
   - Ensure `ollama.embedding.dimension` matches the model

## Testing

1. **Test Endpoint**
   ```bash
   curl -X GET http://localhost:8080/test-integration/check-ollama4j
   ```

2. **Direct API Test**
   ```bash
   curl -X POST http://localhost:8080/chat/test-query \
     -H "Content-Type: application/json" \
     -d '{"prompt":"Hello, how are you?","history":[]}'
   ```

## Implementation Details

- **Qualification Annotations**
  - `@AiConfiguredService` - Main qualifier for AI services
  - `@LangchainService` - Qualifier for langchain4j implementations
  - `@OllamaImplementation` - Qualifier for Ollama-specific implementations

- **Key Classes**
  - `OllamaClientFactory` - Creates and configures Ollama clients
  - `AiProducer` - Produces the appropriate AI services based on configuration
  - `LangchainEmbeddingService` - Langchain4j-based embedding service for generating text embeddings
  - `LangchainQueryService` - Langchain4j-based query service for chat completions

## Further Improvements

Potential future improvements:
1. Add more comprehensive error handling
2. Add model warmup on startup
3. Create dedicated health check for Ollama
4. Add support for additional models beyond llama3