# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

chzzk-bridge is a high-performance standalone bridge server for CHZZK (Naver's streaming platform) integration in multi-server environments. It handles OAuth authentication, session management, and real-time event streaming via SSE.

## Build & Run Commands

```bash
# Build
./gradlew build

# Run tests
./gradlew test

# Run a single test class
./gradlew test --tests "io.typst.chzzk.bridge.SessionStoreTest"

# Run a specific test method
./gradlew test --tests "io.typst.chzzk.bridge.SessionStoreTest.testIssueState"

# Generate JOOQ sources (requires running DB)
./gradlew generateJooq

# Create shadow JAR for deployment
./gradlew shadowJar

# Run application (requires CHZZK_CLIENT_ID and CHZZK_CLIENT_SECRET env vars)
./gradlew run
```

## Required Environment Variables

- `CHZZK_CLIENT_ID`: CHZZK OAuth client ID
- `CHZZK_CLIENT_SECRET`: CHZZK OAuth client secret

## Architecture

### Dual-Server Design
The application runs two Ktor Netty servers:
- **OAuth Server** (port 39680): Handles external CHZZK OAuth callbacks
- **API Server** (port 39681, localhost only): Internal API for Minecraft server integration

### Core Components

**ChzzkService** (`ChzzkService.kt`)
- Central orchestrator managing sessions, tokens, and gateway interactions
- Coordinates between repository, session store, and CHZZK gateway

**Gateway Pattern** (`chzzk/`)
- `ChzzkGateway`: Interface abstracting CHZZK API operations (login, token refresh, session connect)
- `Chzzk4jGateway`: Production implementation using chzzk4j library
- `TestChzzkGateway`: Test double simulating OAuth flows without real API calls

**Repository Layer** (`repository/`)
- `BridgeRepository`: Interface for token/message persistence
- `SQLiteBridgeRepository`: SQLite implementation with Flyway migrations and JOOQ

**SessionStore** (`SessionStore.kt`)
- Thread-safe session management using `ConcurrentHashMap` and `AtomicReference<PersistentMap>`
- Issues cryptographically random state tokens for OAuth CSRF protection
- Manages `CompletableFuture<ChzzkSessionGateway>` for async session creation

### API Endpoints (KTorExtension.kt)

| Endpoint              | Method | Purpose                                            |
|-----------------------|--------|----------------------------------------------------|
| `/oauth_callback`     | GET    | CHZZK OAuth redirect handler                       |
| `/api/v1/subscribe`   | POST   | Subscribe user (returns interlock URL if no token) |
| `/api/v1/unsubscribe` | POST   | Remove user session                                |
| `/api/v1/sse`         | SSE    | Stream CHZZK events to clients                     |

### Database Schema
SQLite database with Flyway migrations in `src/main/resources/db/migration/`:
- `token`: OAuth tokens mapped to Minecraft UUIDs
- `event`: CHZZK chat/donation events for SSE replay

JOOQ-generated sources are in `src-gen/` (excluded from coverage).

## Testing Strategy

Tests use mock implementations per ADR-0001:
- `TestChzzkGateway`: Simulates OAuth token exchange without browser/network
- `TestChzzkSessionGateway`: Mock session for event testing

Test utilities in `src/test/kotlin/.../`:
- `CoroutineExtension`: JUnit5 extension for coroutine test scope
- `KTorTestExtension`: Configures Ktor test application with mock dependencies

## Key Dependencies

- **chzzk4j**: Naver CHZZK API client library
- **Ktor**: HTTP server with SSE support
- **JOOQ + SQLite**: Type-safe SQL with embedded database
- **kotlinx-collections-immutable**: Lock-free concurrent state management
