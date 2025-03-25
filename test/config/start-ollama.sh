#!/bin/bash
# Exit on error
set -e

echo "Starting Ollama server in Docker container..."
# Note: When running in Docker, we don't need to manually start ollama serve
# as the official image already does this

# Set up a trap to ensure we run ollama serve in the foreground at the end
trap 'exec ollama serve' EXIT

# Pull required models
echo "Pulling required Ollama models inside Docker container..."
echo "This may take some time if the models are not already downloaded."

# Pull the required model for both embeddings and chat
echo "Pulling llama3 model for embeddings and chat..."
echo "This may take some time on first run (several minutes)"
echo "Downloading from Hugging Face..."
# Increase timeout for model pulling in Docker environment
OLLAMA_TIMEOUT=300 ollama pull llama3:latest

# Also pull a smaller model as fallback (faster startup)
echo "Pulling tinyllama model as fallback..."
OLLAMA_TIMEOUT=120 ollama pull tinyllama:latest

# Verify models are available
echo "Verifying models are available..."
MODELS=$(ollama list)
echo "$MODELS"

# Check if models exist
if echo "$MODELS" | grep -q "llama3"; then
    echo "✅ llama3 model is available"
else
    echo "❌ ERROR: llama3 model is not available!"
    exit 1
fi

if echo "$MODELS" | grep -q "tinyllama"; then
    echo "✅ tinyllama model is available"
else
    echo "⚠️ Warning: tinyllama model is not available (fallback model)"
fi

# Print info about available endpoints
echo "✅ Ollama setup complete!"
echo "For debugging, visit http://localhost:11434 in your browser"
echo ""
echo "Ollama API endpoints:"
echo "- Chat generation: http://localhost:11434/api/generate"
echo "- Text embeddings: http://localhost:11434/api/embeddings"
echo "- Model listing: http://localhost:11434/api/tags"
echo ""
echo "Container configuration uses:"
echo "ollama.endpoint=http://localhost:11434"
echo "ollama.embedding.model=llama3"
echo "ollama.embedding.dimension=4096"
echo "ollama.chat.model=llama3"

# The trap will ensure we run ollama serve when the script completes
