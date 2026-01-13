#!/bin/bash

# Script to initialize Elasticsearch index for query logs

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

echo "==================================="
echo "Elasticsearch Index Initialization"
echo "==================================="
echo ""

# Check if Elasticsearch is running
echo "Checking Elasticsearch connection..."
if ! curl -s http://localhost:9200/_cluster/health > /dev/null 2>&1; then
    echo "ERROR: Cannot connect to Elasticsearch at http://localhost:9200"
    echo "Please ensure Elasticsearch is running (e.g., using docker compose up -d)"
    exit 1
fi

echo "Elasticsearch is running"
echo ""

# Navigate to db-server directory
cd "$PROJECT_ROOT/db-server"

# Run the Kotlin initialization script
echo "Initializing Elasticsearch index..."
./gradlew runInitElasticsearch "$@"

echo ""
echo "Index initialization completed!"
