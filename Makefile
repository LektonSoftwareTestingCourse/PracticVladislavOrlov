# Переменные
DOCKER_COMPOSE = docker compose
MVN = mvn -f services/pom.xml
NPM_DIR = services/dashboard
SERVICE ?= gateway

ifeq ($(OS),Windows_NT)
    COPY_CMD = @if not exist .env cmd /c "copy /Y .env.example .env >nul"
    SMOKE_CMD = pwsh -ExecutionPolicy Bypass -File scripts/smoke-test.ps1
else
    COPY_CMD = test -f .env || cp .env.example .env
    SMOKE_CMD = ./scripts/smoke-test.sh
endif

# Специальные цели
.PHONY: run stop clean build status logs smoke smoke-win test lint
.PHONY: mvn-all mvn-clean mvn-test mvn-lint mvn-service api-test
.PHONY: npm-install npm-lint npm-test npm-build

help:
	@echo "Available commands:"
	@echo ""
	@echo "Docker:"
	@echo "  make run           - Up all containers"
	@echo "  make stop          - Stop all containers"
	@echo "  make clean         - Remove containers, images and volumes"
	@echo "  make build         - Rebuild all images"
	@echo "  make status        - Containers status"
	@echo "  make logs          - Show logs (SERVICE=gateway)"
	@echo "  make smoke         - Run smoke tests"
	@echo ""
	@echo "General"
	@echo "  make test          - Run all tests (Maven + NPM)"
	@echo "  make lint          - Run all linters (Maven + NPM)"
	@echo ""
	@echo "Maven (all modules):"
	@echo "  make mvn-all       - Build all modules"
	@echo "  make mvn-clean     - Clean all modules"
	@echo "  make mvn-test      - Run all tests"
	@echo "  make mvn-lint      - Run checkstyle"
	@echo ""
	@echo "Maven (single service, SERVICE=gateway):"
	@echo "  make mvn-service   - Build one service + deps"
	@echo ""
	@echo "Frontend (dashboard):"
	@echo "  make npm-install   - Install npm dependencies"
	@echo "  make npm-lint      - Run ESLint"
	@echo "  make npm-test      - Run tests"
	@echo "  make npm-build     - Build production bundle"


# Docker
.env:
	$(COPY_CMD)

run: .env
	$(DOCKER_COMPOSE) up -d

stop:
	$(DOCKER_COMPOSE) down

clean:
	$(DOCKER_COMPOSE) down -v --remove-orphans

build:
	$(DOCKER_COMPOSE) build

status:
	$(DOCKER_COMPOSE) ps

logs:
	$(DOCKER_COMPOSE) logs -f $(SERVICE)

smoke:
	$(SMOKE_CMD)

# Общее
test: mvn-test npm-test

lint: mvn-lint npm-lint

# Maven
mvn-all:
	$(MVN) package

mvn-clean:
	$(MVN) clean

mvn-test:
	$(MVN) test

mvn-lint:
	$(MVN) checkstyle:check

# API-тесты практики 5 (нужен поднятый стек: make run)
api-test:
	$(MVN) -Papi-tests test

mvn-service:
	$(MVN) -P$(SERVICE) package

# Frontend
npm-install:
	cd $(NPM_DIR) && npm install

npm-lint:
	cd $(NPM_DIR) && npm run lint

npm-test:
	cd $(NPM_DIR) && npm test -- --run

npm-build:
	cd $(NPM_DIR) && npm run build
