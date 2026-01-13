#!/bin/bash

# Script to setup Elasticsearch, Kibana, and optionally DB Server using docker compose

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

# Parse arguments
START_DB_SERVER=false
if [ "$1" = "--with-db-server" ]; then
    START_DB_SERVER=true
fi

echo "=========================================="
echo "DB Project - Elasticsearch Setup"
echo "=========================================="
echo ""

# Navigate to project root
cd "$PROJECT_ROOT"

# Step 1: Start services using docker compose
if [ "$START_DB_SERVER" = true ]; then
    echo "Step 1: Starting Elasticsearch, Kibana, and DB Server with docker compose..."
    docker compose up -d --build
else
    echo "Step 1: Starting Elasticsearch and Kibana with docker compose..."
    docker compose up -d elasticsearch kibana
fi

echo ""
echo "Waiting for Elasticsearch to be ready..."
for i in {1..30}; do
    if curl -s http://localhost:9200/_cluster/health > /dev/null 2>&1; then
        echo "Elasticsearch is ready!"
        break
    fi
    if [ $i -eq 30 ]; then
        echo "ERROR: Elasticsearch did not start in time"
        echo "Check logs with: docker compose logs elasticsearch"
        exit 1
    fi
    echo "Waiting... ($i/30)"
    sleep 2
done

echo ""
echo "Waiting for Kibana to be ready..."
for i in {1..30}; do
    if curl -s http://localhost:5601/api/status > /dev/null 2>&1; then
        echo "Kibana is ready!"
        break
    fi
    if [ $i -eq 30 ]; then
        echo "WARNING: Kibana did not start in time, but continuing..."
        break
    fi
    echo "Waiting... ($i/30)"
    sleep 2
done

# Display Elasticsearch info
echo ""
echo "Elasticsearch cluster info:"
curl -s http://localhost:9200 | grep -E "name|cluster_name|version" || true
echo ""

# Step 2: Initialize Elasticsearch index
echo ""
echo "Step 2: Initializing Elasticsearch index..."
cd "$PROJECT_ROOT/db-server"
./gradlew runInitElasticsearch

echo ""
echo "=========================================="
echo "Setup Complete!"
echo "=========================================="
echo ""
if [ "$START_DB_SERVER" = true ]; then
    echo "Services are running:"
    echo "  - Elasticsearch: http://localhost:9200"
    echo "  - Kibana: http://localhost:5601"
    echo "  - DB Server: localhost:9000"
    echo ""
    echo "Next steps:"
    echo "  1. Connect to DB Server on port 9000"
    echo "  2. Access Kibana at http://localhost:5601 to visualize query logs"
    echo ""
else
    echo "Services are running:"
    echo "  - Elasticsearch: http://localhost:9200"
    echo "  - Kibana: http://localhost:5601"
    echo ""
    echo "Next steps:"
    echo "  1. Run example: cd db-server && ./gradlew runQueryLogExample"
    echo "  2. Start DB server locally: cd db-server && ./gradlew run"
    echo "     OR start all services with: ./scripts/setup-elasticsearch.sh --with-db-server"
    echo "  3. Access Kibana at http://localhost:5601 to visualize query logs"
    echo ""
fi
echo "Useful commands:"
echo "  - View logs: docker compose logs -f"
echo "  - Stop services: docker compose down"
echo "  - Restart services: docker compose restart"
if [ "$START_DB_SERVER" = true ]; then
    echo "  - Rebuild db-server: docker compose up -d --build db-server"
fi
echo ""
