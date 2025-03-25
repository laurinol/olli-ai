#!/bin/bash

# Test script for direct Ollama API interaction
echo "Testing direct Ollama API access..."

# Check if Ollama is running
echo "Checking if Ollama service is running..."
if ! pgrep -f "ollama serve" > /dev/null; then
    echo "❌ Ollama service is not running."
    echo "   Please start it with: ./test/config/start-ollama.sh"
    exit 1
fi

# Test the /api/tags endpoint
echo "Testing /api/tags endpoint..."
TAGS_RESPONSE=$(curl -s -o /dev/null -w "%{http_code}" http://localhost:11434/api/tags)
if [ "$TAGS_RESPONSE" != "200" ]; then
    echo "❌ Failed to access /api/tags endpoint. Status code: $TAGS_RESPONSE"
    exit 1
fi
echo "✅ Successfully accessed /api/tags endpoint"

# Test the /api/embeddings endpoint
echo "Testing /api/embeddings endpoint..."
EMBED_RESPONSE=$(curl -s -X POST http://localhost:11434/api/embeddings \
    -H "Content-Type: application/json" \
    -d '{"model":"llama3","prompt":"test embedding"}' \
    -o /dev/null -w "%{http_code}")

if [ "$EMBED_RESPONSE" != "200" ]; then
    echo "❌ Failed to access /api/embeddings endpoint. Status code: $EMBED_RESPONSE"
    echo "   Model llama3 may not be available."
    echo "   Try running: ollama pull llama3"
    exit 1
fi
echo "✅ Successfully accessed /api/embeddings endpoint"

# Test the /api/generate endpoint
echo "Testing /api/generate endpoint..."
GENERATE_RESPONSE=$(curl -s -X POST http://localhost:11434/api/generate \
    -H "Content-Type: application/json" \
    -d '{"model":"llama3","prompt":"Say hello","stream":false}' \
    -o /dev/null -w "%{http_code}")

if [ "$GENERATE_RESPONSE" != "200" ]; then
    echo "❌ Failed to access /api/generate endpoint. Status code: $GENERATE_RESPONSE"
    echo "   Model llama3 may not be available."
    echo "   Try running: ollama pull llama3"
    exit 1
fi
echo "✅ Successfully accessed /api/generate endpoint"

echo "All Ollama API endpoints are accessible!"
echo ""
echo "If you're still experiencing issues with langchain4j integration:"
echo "1. Make sure the llama3 model is pulled: ollama pull llama3"
echo "2. Restart the Ollama service: pkill ollama && ollama serve"
echo "3. Check the application.properties configuration:"
echo "   - ollama.endpoint=http://localhost:11434"
echo "   - ollama.embedding.model=llama3"
echo "   - ollama.chat.model=llama3"
echo ""
echo "You can also test the endpoint directly with the application:"
echo "curl -X GET http://localhost:8080/test-integration/check-ollama4j"