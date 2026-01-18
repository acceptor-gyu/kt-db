#!/bin/bash

# API 서버 주소
API_URL="http://localhost:8080"

echo "======================================"
echo "API 테스트 스크립트"
echo "======================================"
echo ""

# 색상 정의
GREEN='\033[0;32m'
BLUE='\033[0;34m'
RED='\033[0;31m'
NC='\033[0m' # No Color

# 헬퍼 함수
print_step() {
    echo -e "${BLUE}[$1]${NC} $2"
}

print_success() {
    echo -e "${GREEN}✓${NC} $1"
}

print_error() {
    echo -e "${RED}✗${NC} $1"
}

# DB 서버 연결 확인
print_step "0" "DB 서버 연결 확인"
curl -s -X GET "${API_URL}/api/tables/ping" | jq '.'
echo ""
sleep 1

# ==========================================
# 시나리오 1: 사용자 테이블 (users)
# ==========================================
print_step "1" "사용자 테이블 생성"
curl -s -X POST "${API_URL}/api/tables/create" \
  -H "Content-Type: application/json" \
  -d '{
    "query": "CREATE TABLE users (id INT, name VARCHAR, email VARCHAR, age INT, active BOOLEAN)"
  }' | jq '.'
echo ""
sleep 1

print_step "2" "사용자 데이터 삽입 - John"
curl -s -X POST "${API_URL}/api/tables/insert" \
  -H "Content-Type: application/json" \
  -d '{
    "query": "INSERT INTO users VALUES (id=\"1\", name=\"John Doe\", email=\"john@example.com\", age=\"30\", active=\"true\")"
  }' | jq '.'
echo ""
sleep 0.5

print_step "3" "사용자 데이터 삽입 - Jane"
curl -s -X POST "${API_URL}/api/tables/insert" \
  -H "Content-Type: application/json" \
  -d '{
    "query": "INSERT INTO users VALUES (id=\"2\", name=\"Jane Smith\", email=\"jane@example.com\", age=\"25\", active=\"true\")"
  }' | jq '.'
echo ""
sleep 0.5

print_step "4" "사용자 데이터 삽입 - Bob"
curl -s -X POST "${API_URL}/api/tables/insert" \
  -H "Content-Type: application/json" \
  -d '{
    "query": "INSERT INTO users VALUES (id=\"3\", name=\"Bob Wilson\", email=\"bob@example.com\", age=\"35\", active=\"false\")"
  }' | jq '.'
echo ""
sleep 0.5

print_step "5" "사용자 데이터 삽입 - Alice"
curl -s -X POST "${API_URL}/api/tables/insert" \
  -H "Content-Type: application/json" \
  -d '{
    "query": "INSERT INTO users VALUES (id=\"4\", name=\"Alice Brown\", email=\"alice@example.com\", age=\"28\", active=\"true\")"
  }' | jq '.'
echo ""
sleep 0.5

print_step "6" "사용자 데이터 조회"
curl -s -X GET "${API_URL}/api/tables/select?query=SELECT%20*%20FROM%20users" | jq '.'
echo ""
sleep 1

print_step "7" "EXPLAIN - 사용자 테이블 전체 조회"
curl -s -X GET "${API_URL}/api/tables/query-plan?query=EXPLAIN%20SELECT%20*%20FROM%20users" | jq '.'
echo ""
sleep 1

# ==========================================
# 시나리오 2: 상품 테이블 (products)
# ==========================================
print_step "8" "상품 테이블 생성"
curl -s -X POST "${API_URL}/api/tables/create" \
  -H "Content-Type: application/json" \
  -d '{
    "query": "CREATE TABLE products (id INT, name VARCHAR, price INT, stock INT, available BOOLEAN)"
  }' | jq '.'
echo ""
sleep 1

print_step "9" "상품 데이터 삽입 - Laptop"
curl -s -X POST "${API_URL}/api/tables/insert" \
  -H "Content-Type: application/json" \
  -d '{
    "query": "INSERT INTO products VALUES (id=\"101\", name=\"Laptop\", price=\"1200\", stock=\"15\", available=\"true\")"
  }' | jq '.'
echo ""
sleep 0.5

print_step "10" "상품 데이터 삽입 - Mouse"
curl -s -X POST "${API_URL}/api/tables/insert" \
  -H "Content-Type: application/json" \
  -d '{
    "query": "INSERT INTO products VALUES (id=\"102\", name=\"Wireless Mouse\", price=\"25\", stock=\"50\", available=\"true\")"
  }' | jq '.'
echo ""
sleep 0.5

print_step "11" "상품 데이터 삽입 - Keyboard"
curl -s -X POST "${API_URL}/api/tables/insert" \
  -H "Content-Type: application/json" \
  -d '{
    "query": "INSERT INTO products VALUES (id=\"103\", name=\"Mechanical Keyboard\", price=\"80\", stock=\"0\", available=\"false\")"
  }' | jq '.'
echo ""
sleep 0.5

print_step "12" "상품 데이터 삽입 - Monitor"
curl -s -X POST "${API_URL}/api/tables/insert" \
  -H "Content-Type: application/json" \
  -d '{
    "query": "INSERT INTO products VALUES (id=\"104\", name=\"4K Monitor\", price=\"350\", stock=\"8\", available=\"true\")"
  }' | jq '.'
echo ""
sleep 0.5

print_step "13" "상품 데이터 삽입 - Headphones"
curl -s -X POST "${API_URL}/api/tables/insert" \
  -H "Content-Type: application/json" \
  -d '{
    "query": "INSERT INTO products VALUES (id=\"105\", name=\"Bluetooth Headphones\", price=\"120\", stock=\"20\", available=\"true\")"
  }' | jq '.'
echo ""
sleep 0.5

print_step "14" "상품 데이터 조회"
curl -s -X GET "${API_URL}/api/tables/select?query=SELECT%20*%20FROM%20products" | jq '.'
echo ""
sleep 1

print_step "15" "EXPLAIN - 상품 테이블 전체 조회"
curl -s -X GET "${API_URL}/api/tables/query-plan?query=EXPLAIN%20SELECT%20*%20FROM%20products" | jq '.'
echo ""
sleep 1

# ==========================================
# 시나리오 3: 주문 테이블 (orders)
# ==========================================
print_step "16" "주문 테이블 생성"
curl -s -X POST "${API_URL}/api/tables/create" \
  -H "Content-Type: application/json" \
  -d '{
    "query": "CREATE TABLE orders (order_id INT, user_id INT, product_id INT, quantity INT, total_price INT)"
  }' | jq '.'
echo ""
sleep 1

print_step "17" "주문 데이터 삽입 - Order 1"
curl -s -X POST "${API_URL}/api/tables/insert" \
  -H "Content-Type: application/json" \
  -d '{
    "query": "INSERT INTO orders VALUES (order_id=\"1001\", user_id=\"1\", product_id=\"101\", quantity=\"1\", total_price=\"1200\")"
  }' | jq '.'
echo ""
sleep 0.5

print_step "18" "주문 데이터 삽입 - Order 2"
curl -s -X POST "${API_URL}/api/tables/insert" \
  -H "Content-Type: application/json" \
  -d '{
    "query": "INSERT INTO orders VALUES (order_id=\"1002\", user_id=\"2\", product_id=\"102\", quantity=\"2\", total_price=\"50\")"
  }' | jq '.'
echo ""
sleep 0.5

print_step "19" "주문 데이터 삽입 - Order 3"
curl -s -X POST "${API_URL}/api/tables/insert" \
  -H "Content-Type: application/json" \
  -d '{
    "query": "INSERT INTO orders VALUES (order_id=\"1003\", user_id=\"1\", product_id=\"104\", quantity=\"1\", total_price=\"350\")"
  }' | jq '.'
echo ""
sleep 0.5

print_step "20" "주문 데이터 조회"
curl -s -X GET "${API_URL}/api/tables/select?query=SELECT%20*%20FROM%20orders" | jq '.'
echo ""
sleep 1

print_step "21" "EXPLAIN - 주문 테이블 조회"
curl -s -X GET "${API_URL}/api/tables/query-plan?query=EXPLAIN%20SELECT%20*%20FROM%20orders" | jq '.'
echo ""
sleep 1

# ==========================================
# 시나리오 4: 로그 테이블 (logs)
# ==========================================
print_step "22" "로그 테이블 생성"
curl -s -X POST "${API_URL}/api/tables/create" \
  -H "Content-Type: application/json" \
  -d '{
    "query": "CREATE TABLE logs (log_id INT, user_id INT, action VARCHAR, created_at TIMESTAMP)"
  }' | jq '.'
echo ""
sleep 1

print_step "23" "로그 데이터 삽입 - Log 1"
curl -s -X POST "${API_URL}/api/tables/insert" \
  -H "Content-Type: application/json" \
  -d '{
    "query": "INSERT INTO logs VALUES (log_id=\"1\", user_id=\"1\", action=\"login\", created_at=\"2024-01-15T10:30:00Z\")"
  }' | jq '.'
echo ""
sleep 0.5

print_step "24" "로그 데이터 삽입 - Log 2"
curl -s -X POST "${API_URL}/api/tables/insert" \
  -H "Content-Type: application/json" \
  -d '{
    "query": "INSERT INTO logs VALUES (log_id=\"2\", user_id=\"1\", action=\"view_product\", created_at=\"2024-01-15T10:35:00Z\")"
  }' | jq '.'
echo ""
sleep 0.5

print_step "25" "로그 데이터 삽입 - Log 3"
curl -s -X POST "${API_URL}/api/tables/insert" \
  -H "Content-Type: application/json" \
  -d '{
    "query": "INSERT INTO logs VALUES (log_id=\"3\", user_id=\"2\", action=\"login\", created_at=\"2024-01-15T11:00:00Z\")"
  }' | jq '.'
echo ""
sleep 0.5

print_step "26" "로그 데이터 삽입 - Log 4"
curl -s -X POST "${API_URL}/api/tables/insert" \
  -H "Content-Type: application/json" \
  -d '{
    "query": "INSERT INTO logs VALUES (log_id=\"4\", user_id=\"1\", action=\"purchase\", created_at=\"2024-01-15T10:40:00Z\")"
  }' | jq '.'
echo ""
sleep 0.5

print_step "27" "로그 데이터 조회"
curl -s -X GET "${API_URL}/api/tables/select?query=SELECT%20*%20FROM%20logs" | jq '.'
echo ""
sleep 1

print_step "28" "EXPLAIN - 로그 테이블 조회"
curl -s -X GET "${API_URL}/api/tables/query-plan?query=EXPLAIN%20SELECT%20*%20FROM%20logs" | jq '.'
echo ""
sleep 1

# ==========================================
# 시나리오 5: 직원 테이블 (employees)
# ==========================================
print_step "29" "직원 테이블 생성"
curl -s -X POST "${API_URL}/api/tables/create" \
  -H "Content-Type: application/json" \
  -d '{
    "query": "CREATE TABLE employees (emp_id INT, name VARCHAR, department VARCHAR, salary INT, hired_at TIMESTAMP)"
  }' | jq '.'
echo ""
sleep 1

print_step "30" "직원 데이터 삽입 - Employee 1"
curl -s -X POST "${API_URL}/api/tables/insert" \
  -H "Content-Type: application/json" \
  -d '{
    "query": "INSERT INTO employees VALUES (emp_id=\"1001\", name=\"David Lee\", department=\"Engineering\", salary=\"90000\", hired_at=\"2022-03-15T09:00:00Z\")"
  }' | jq '.'
echo ""
sleep 0.5

print_step "31" "직원 데이터 삽입 - Employee 2"
curl -s -X POST "${API_URL}/api/tables/insert" \
  -H "Content-Type: application/json" \
  -d '{
    "query": "INSERT INTO employees VALUES (emp_id=\"1002\", name=\"Sarah Kim\", department=\"Marketing\", salary=\"75000\", hired_at=\"2021-06-01T09:00:00Z\")"
  }' | jq '.'
echo ""
sleep 0.5

print_step "32" "직원 데이터 삽입 - Employee 3"
curl -s -X POST "${API_URL}/api/tables/insert" \
  -H "Content-Type: application/json" \
  -d '{
    "query": "INSERT INTO employees VALUES (emp_id=\"1003\", name=\"Michael Park\", department=\"Engineering\", salary=\"95000\", hired_at=\"2020-01-20T09:00:00Z\")"
  }' | jq '.'
echo ""
sleep 0.5

print_step "33" "직원 데이터 조회"
curl -s -X GET "${API_URL}/api/tables/select?query=SELECT%20*%20FROM%20employees" | jq '.'
echo ""
sleep 1

print_step "34" "EXPLAIN - 직원 테이블 조회"
curl -s -X GET "${API_URL}/api/tables/query-plan?query=EXPLAIN%20SELECT%20*%20FROM%20employees" | jq '.'
echo ""
sleep 1

# ==========================================
# 요약
# ==========================================
echo ""
echo "======================================"
echo "테스트 완료!"
echo "======================================"
echo ""
print_success "생성된 테이블: users, products, orders, logs, employees"
print_success "삽입된 데이터: 총 19개 레코드"
print_success "실행된 EXPLAIN 쿼리: 5개"
echo ""
echo "데이터 확인:"
echo "  - ./data/ 디렉토리에 .dat 파일 생성 확인"
echo "  - ls -lh ./data/"
echo ""
