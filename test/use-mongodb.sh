#!/bin/bash

# Script to set up the environment for using MongoDB instead of Firestore

# Start MongoDB container
echo "Starting MongoDB container..."
cd $(dirname $0)
docker-compose up -d mongodb

# Set environment variables for the application
export DATABASE_PROVIDER=mongodb
export MONGODB_CONNECTION_STRING=mongodb://admin:password@localhost:27017
export MONGODB_DATABASE=olli

echo "Environment set up for MongoDB."
echo "To start the application with MongoDB, run:"
echo "mvn quarkus:dev"