#!/bin/bash
# Script to pull the tinyllama model for Ollama in Docker
# Use this script only if you need to manually pull models to an already running container

echo "Pulling tinyllama model for Ollama in Docker container..."
echo "This may take several minutes on first download."

# Check if Docker is running
if ! command -v docker &> /dev/null; then
    echo "❌ Docker not found. Please install it first."
    exit 1
fi

# Check if Ollama container is running
CONTAINER_ID=$(docker ps --filter name=ollama -q)
if [ -z "$CONTAINER_ID" ]; then
    echo "❌ Ollama container is not running."
    echo "   Please start it with: docker-compose -f test/docker-compose.yml up -d"
    exit 1
fi

echo "✅ Found Ollama container: $CONTAINER_ID"
echo ""

# Execute the pull command inside the container
echo "Downloading tinyllama model inside Docker container..."
echo "💡 This will download around 4GB of data, please be patient."

docker exec -it $CONTAINER_ID ollama pull tinyllama:latest

if [ $? -eq 0 ]; then
    echo "✅ Successfully pulled tinyllama model!"
    echo ""
    echo "Available models in container:"
    docker exec -it $CONTAINER_ID ollama list
else
    echo "❌ Failed to pull tinyllama model."
    echo "   Check your internet connection and container status."
    exit 1
fi

# Also pull tinyllama as a fallback
echo ""
echo "Pulling smaller fallback model (tinyllama)..."
docker exec -it $CONTAINER_ID ollama pull tinyllama:latest

echo ""
echo "Models are now ready to use with the backend application."
echo "Note: Models should automatically be pulled when the container starts."
echo "This script is only needed if you need to manually pull models to an existing container."
