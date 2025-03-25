#!/bin/bash

# Check if Ollama server is running
echo "Checking if Ollama server is running..."
OLLAMA_PID=$(pgrep -f "ollama serve" || echo "")
if [ -n "$OLLAMA_PID" ]; then
    echo "✅ Ollama server is running with PID: $OLLAMA_PID"
else
    echo "❌ Ollama server is not running."
    echo "   To start it, run: ./test/config/start-ollama.sh"
    exit 1
fi

# Check if we can connect to Ollama API
echo "Checking Ollama API connection..."
if curl -s -m 5 "http://localhost:11434/api/tags" > /dev/null; then
    echo "✅ Ollama API is reachable at http://localhost:11434"
else
    echo "❌ Ollama API is not reachable at http://localhost:11434"
    echo "   Check if the server is running and the port is correct"
    exit 1
fi

# Check available models
echo "Checking available models..."
MODELS=$(curl -s -m 5 "http://localhost:11434/api/tags")
if echo "$MODELS" | grep -q "llama3"; then
    echo "✅ llama3 model is available"
else
    echo "❌ llama3 model is not available"
    echo "   To pull the model, run: ollama pull llama3"
    exit 1
fi

# Try a simple embedding request
echo "Testing embedding generation..."
EMBEDDING_RESPONSE=$(curl -s -m 10 -X POST "http://localhost:11434/api/embeddings" \
    -H "Content-Type: application/json" \
    -d '{"model": "llama3", "prompt": "test embedding"}')

if echo "$EMBEDDING_RESPONSE" | grep -q "embedding"; then
    echo "✅ Embedding generation successful"
else
    echo "❌ Embedding generation failed"
    echo "   Response: $EMBEDDING_RESPONSE"
    exit 1
fi

# Try a simple chat request
echo "Testing chat completion..."
CHAT_RESPONSE=$(curl -s -m 10 -X POST "http://localhost:11434/api/generate" \
    -H "Content-Type: application/json" \
    -d '{"model": "llama3", "prompt": "Say hello", "stream": false}')

if echo "$CHAT_RESPONSE" | grep -q "response"; then
    echo "✅ Chat completion successful"
else
    echo "❌ Chat completion failed"
    echo "   Response: $CHAT_RESPONSE"
    exit 1
fi

echo "✅ All Ollama checks passed successfully!"
echo ""
echo "Server Status: Running"
echo "API Endpoint: http://localhost:11434"
echo "Available Models: llama3"
echo ""
echo "To use this with the application, ensure the following properties are set:"
echo "ollama.endpoint=http://localhost:11434"
echo "ollama.embedding.model=llama3"
echo "ollama.embedding.dimension=4096"
echo "ollama.chat.model=llama3"