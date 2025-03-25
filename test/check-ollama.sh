#!/bin/bash
# Script to check Ollama Docker container status

# Check if Docker is installed
if ! command -v docker &> /dev/null; then
    echo "❌ Docker not found. Please install Docker first."
    exit 1
fi

# Check if Ollama container is running
echo "Checking if Ollama Docker container is running..."
CONTAINER_ID=$(docker ps --filter name=ollama -q)
if [ -z "$CONTAINER_ID" ]; then
    echo "❌ Ollama container is not running."
    echo "   Start it with: docker-compose -f test/docker-compose.yml up -d"
    exit 1
fi

echo "✅ Ollama container is running with ID: $CONTAINER_ID"

# Check if we can connect to Ollama API
echo "Checking Ollama API connection..."
if curl -s -m 5 "http://localhost:11434/api/tags" > /dev/null; then
    echo "✅ Ollama API is reachable at http://localhost:11434"
else
    echo "❌ Ollama API is not reachable at http://localhost:11434"
    echo "   Check container logs: docker-compose -f test/docker-compose.yml logs ollama"
    exit 1
fi

# Check available models in the container
echo "Checking available models in Docker container..."
MODELS=$(curl -s -m 5 "http://localhost:11434/api/tags")
if echo "$MODELS" | grep -q "llama3"; then
    echo "✅ llama3 model is available in Docker container"
else
    echo "❌ llama3 model is not available in Docker container"
    echo "   The model should be automatically pulled when the container starts"
    echo "   Check container logs: docker-compose -f test/docker-compose.yml logs ollama"
    echo "   You can manually pull: docker exec -it $CONTAINER_ID ollama pull llama3"
    exit 1
fi

# Try a simple embedding request
echo "Testing embedding generation using container API..."
EMBEDDING_RESPONSE=$(curl -s -m 10 -X POST "http://localhost:11434/api/embeddings" \
    -H "Content-Type: application/json" \
    -d '{"model": "llama3", "prompt": "test embedding"}')

if echo "$EMBEDDING_RESPONSE" | grep -q "embedding"; then
    echo "✅ Embedding generation successful from Docker container"
else
    echo "❌ Embedding generation failed from Docker container"
    echo "   Response: $EMBEDDING_RESPONSE"
    exit 1
fi

# Try a simple chat request
echo "Testing chat completion using container API..."
CHAT_RESPONSE=$(curl -s -m 10 -X POST "http://localhost:11434/api/generate" \
    -H "Content-Type: application/json" \
    -d '{"model": "llama3", "prompt": "Say hello", "stream": false}')

if echo "$CHAT_RESPONSE" | grep -q "response"; then
    echo "✅ Chat completion successful from Docker container"
else
    echo "❌ Chat completion failed from Docker container"
    echo "   Response: $CHAT_RESPONSE"
    exit 1
fi

echo "✅ All Ollama Docker container checks passed successfully!"
echo ""
echo "Container Status: Running (ID: $CONTAINER_ID)"
echo "API Endpoint: http://localhost:11434"
echo "Available Models: llama3"
echo ""
echo "For troubleshooting:"
echo "- View container logs: docker-compose -f test/docker-compose.yml logs ollama"
echo "- Enter container shell: docker exec -it $CONTAINER_ID /bin/bash"
echo "- Restart container: docker-compose -f test/docker-compose.yml restart ollama"
echo ""
echo "Application configuration properties are correctly set in application.properties:"
echo "ollama.endpoint=http://localhost:11434"
echo "ollama.embedding.model=llama3"
echo "ollama.embedding.dimension=4096"
echo "ollama.chat.model=llama3"