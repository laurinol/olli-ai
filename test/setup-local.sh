#!/bin/bash
export $(cat backend/.env | xargs)
docker-compose -p olli-ai -f test/docker-compose.yml up -d
cd frontend && npm install && npm run build
cd ../backend && mvn compile && mvn quarkus:dev
