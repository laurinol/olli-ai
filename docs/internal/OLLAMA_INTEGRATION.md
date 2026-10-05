# Ollama Integration

This document describes how to integrate the Olli backend with Ollama, a lightweight LLM server.

## Configuration Options

The Olli backend supports two different implementations for Ollama integration:

1. **Langchain4j Implementation** (Default)
   - Uses the langchain4j library as an intermediate layer
   - Good for complex chains and advanced features
   - Can be slower and sometimes experiences timeout issues

2. **Direct Implementation**
   - Directly calls the Ollama REST API
   - Faster and more reliable, especially for simple use cases
   - Avoids the timeout issues that can occur with langchain4j

## Setup

### Prerequisites

1. Install and run Ollama:
   ```bash
   # Install Ollama (macOS)
   curl -fsSL https://ollama.com/install.sh | sh
   
   # Start Ollama
   ollama serve
   ```

2. Pull the required models:
   ```bash
   # Pull the models specified in your configuration
   ollama pull tinyllama
   ```

### Configuration

The following properties control Ollama integration:

```properties
# Ollama server endpoint
%dev.ollama.endpoint=http://localhost:11434

# Models to use
%dev.ollama.embedding.model=tinyllama
%dev.ollama.chat.model=tinyllama

# Embedding dimension (depends on the model)
%dev.ollama.embedding.dimension=768

# Implementation to use: "langchain" or "direct"
%dev.ollama.implementation=direct

# Timeout for connections
%dev.ollama.connection.timeout.seconds=60
```

## Choosing an Implementation

To switch between implementations:

1. Using environment variables:
   ```bash
   # Use direct implementation
   export OLLAMA_IMPLEMENTATION=direct
   
   # Use langchain4j implementation
   export OLLAMA_IMPLEMENTATION=langchain
   ```

2. Using application properties:
   ```properties
   # In application.properties or application-dev.properties
   ollama.implementation=direct  # or "langchain"
   ```

3. Using helper scripts (for development):
   ```bash
   # Switch to direct implementation
   ./test/use-direct-implementation.sh
   
   # Switch to langchain implementation
   ./test/use-langchain-implementation.sh
   ```

## Testing

Several test scripts are provided to test and compare the different implementations:

1. Test the configuration:
   ```bash
   ./test/test-ai-config.sh
   ```

2. Test direct API calls:
   ```bash
   ./test/test-direct-api.sh "Your query here"
   ```

3. Test and compare both implementations:
   ```bash
   ./test/compare-implementations.sh "Your query here"
   ```

4. Test direct implementation only:
   ```bash
   ./test/test-direct-implementation.sh
   ```

## Implementation Details

### Direct Implementation Classes

1. `DirectOllamaEmbeddingService` - Direct implementation of the `EmbeddingService` interface.
2. `DirectOllamaQueryService` - Direct implementation of the `QueryService` interface.

These classes use Vert.x WebClient for non-blocking HTTP requests to avoid thread-blocking issues.

### Langchain4j Implementation Classes

1. `LangchainEmbeddingService` - Implementation using langchain4j library.
2. `LangchainQueryService` - Implementation using langchain4j streaming model.

## Troubleshooting

1. **Timeout Issues**:
   - If experiencing timeouts with langchain4j, switch to the direct implementation
   - Increase timeout values in application.properties

2. **Model Issues**:
   - Make sure the models are pulled correctly: `ollama list`
   - For embedding issues, check that the model supports embeddings

3. **Connection Issues**:
   - Verify Ollama is running: `pgrep -f "ollama serve"`
   - Check the endpoint configuration matches where Ollama is running

4. **Debug Endpoints**:
   - Use `/test-integration/check-ai-config` to check configuration
   - Use `/test-integration/direct-ollama` to test direct API
   - Use `/test-integration/simple-ollama` for a simplified response

## Performance Considerations

The direct implementation generally offers better performance for simple queries, 
especially regarding timeout handling and resource usage. However, langchain4j provides 
more advanced features for complex AI workflows. Choose the implementation that best
fits your needs.
