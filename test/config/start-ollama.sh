#!/bin/bash
# Exit on error
set -e

# Check if Ollama is already running
echo "Checking if Ollama is already running..."
RUNNING=$(pgrep -f "ollama serve" || echo "")
if [ -n "$RUNNING" ]; then
    echo "Ollama is already running with PID: $RUNNING"
else
    echo "Starting Ollama server..."
    # Run ollama in the background
    ollama serve &
    
    # Save the PID
    OLLAMA_PID=$!
    echo "Started Ollama server with PID: $OLLAMA_PID"
    
    # Give it a moment to start up
    sleep 5
fi

# Pull both required models
echo "Pulling required Ollama models..."
echo "This may take some time if the models are not already downloaded."

# Pull the embedding model
echo "Pulling llama3 model for embeddings and chat..."
ollama pull llama3

# Verify models are available
echo "Verifying models are available..."
MODELS=$(ollama list)
echo "$MODELS"

# Check if models exist
if echo "$MODELS" | grep -q "llama3"; then
    echo "llama3 model is available"
else
    echo "ERROR: llama3 model is not available!"
    exit 1
fi

echo "Ollama setup complete!"
echo "For debugging, visit http://localhost:11434 in your browser"
echo ""
echo "Ollama API endpoints:"
echo "- Chat generation: http://localhost:11434/api/generate"
echo "- Text embeddings: http://localhost:11434/api/embeddings"
echo "- Model listing: http://localhost:11434/api/tags"
echo ""
echo "Make sure to set the following in application.properties:" 
echo "ollama.endpoint=http://localhost:11434"
echo "ollama.embedding.model=llama3"
echo "ollama.embedding.dimension=4096"
echo "ollama.chat.model=llama3"

# If we started Ollama, wait for it
if [ -n "$OLLAMA_PID" ]; then
    echo "Press Ctrl+C to stop the Ollama server"
    wait $OLLAMA_PID
fi
