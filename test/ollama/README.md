# Ollama Integration Testing

This directory contains tools and documentation for testing the Ollama integration with the backend.

## Prerequisites

1. [Install Ollama](https://ollama.ai/download) on your machine
2. Ensure Ollama is running and accessible at http://localhost:11434

## Starting Ollama

Use the provided script to start Ollama with the required models:

```bash
./test/config/start-ollama.sh
```

This script will:
1. Start the Ollama server if it's not already running
2. Download the required models (llama3)
3. Verify that the models are available

## Checking Ollama Status

To verify that Ollama is properly configured and running:

```bash
./test/check-ollama.sh
```

This script checks:
1. If the Ollama server is running
2. If the API is accessible
3. If the required models are available
4. If basic embedding and chat requests work

## Configuration

The application must be configured to use Ollama in `application.properties`:

```properties
ollama.endpoint=http://localhost:11434
ollama.embedding.model=llama3
ollama.embedding.dimension=4096
ollama.chat.model=llama3
```

## Testing

You can use the test endpoint to verify the Ollama integration:

```bash
curl -X GET http://localhost:8080/test-integration/check-ollama4j
```

This endpoint checks:
1. That the AI services are properly configured
2. The ability to generate embeddings
3. Service availability

## Debugging

For debugging purposes, you can directly interact with the Ollama API:

### List Models
```bash
curl http://localhost:11434/api/tags
```

### Generate Embeddings
```bash
curl -X POST http://localhost:11434/api/embeddings \
  -H "Content-Type: application/json" \
  -d '{"model":"llama3","prompt":"test embedding"}'
```

### Chat Completion
```bash
curl -X POST http://localhost:11434/api/generate \
  -H "Content-Type: application/json" \
  -d '{"model":"llama3","prompt":"Say hello","stream":false}'
```

## Troubleshooting

### Common Issues

1. **Null Response Body Error**: 
   ```
   java.lang.NullPointerException: Cannot invoke "okhttp3.ResponseBody.byteStream()" because the return value of "retrofit2.Response.body()" is null
   ```
   
   This usually indicates that the Ollama server is not running or is not accessible.
   - Check if Ollama is running: `pgrep -f "ollama serve"`
   - Verify API access: `curl http://localhost:11434/api/tags`
   - Ensure the model is available: `ollama list`

2. **Model Not Found Error**:
   If you see errors about models not being found, download them:
   ```bash
   ollama pull llama3
   ```

3. **Connection Issues**:
   - Verify Ollama is running and accessible at the configured endpoint
   - Check if there are any firewalls blocking access to port 11434
   - Restart Ollama if it's unresponsive: `pkill ollama && ollama serve`

4. **Wrong Model Dimensions**:
   - Ensure the `ollama.embedding.dimension` property matches the actual embedding dimensions produced by the model
   - For llama3, the default is 4096
