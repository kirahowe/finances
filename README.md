# Finance Aggregator

A personal finance management application for aggregating transactions from multiple financial institutions, with automated categorization and spending analysis.

## Features

- 🏦 **Multi-Institution Support** - Connect accounts via Plaid (12,000+ institutions)
- 📊 **Transaction Management** - View, filter, and categorize transactions
- 🏷️ **Smart Categorization** - Manual and automated transaction categorization
- 📈 **Spending Analysis** - Track spending by category and time period
- 🔐 **Secure** - Encrypted secrets, user-scoped data isolation

## Quick Start

See [DEVELOPMENT.md](./DEVELOPMENT.md) for complete setup instructions.

### Prerequisites

```bash
# Install required tools
brew install borkdude/brew/babashka  # Secrets management
brew install age                      # Encryption
brew install overmind                 # Process manager
brew install pnpm                     # Package manager

# Install Jabba (Java version manager)
curl -sL https://github.com/shyiko/jabba/raw/master/install.sh | bash
jabba install zulu@21.0.6
```

### First-Time Setup

```bash
# 1. Install frontend dependencies
cd frontend && pnpm install && cd ..

# 2. Configure secrets (interactive)
bb secrets keygen
bb secrets new
# Add your Plaid credentials in the editor

# 3. Start everything
overmind start
```

Access the application at:
- **Frontend**: http://localhost:5173
- **Backend API**: http://localhost:8080

## Architecture

### Backend (Clojure)

Modern functional architecture using:
- **Integrant** - Component lifecycle management
- **Datalevin** - Embedded Datalog database
- **Plaid API** - Financial data aggregation
- **HTTP-Kit** - High-performance HTTP server

See [Backend README](./backend/README.md) for details.

### Frontend (React + Remix)

Type-safe React application using:
- **React Router v7** - Routing and data loading
- **TypeScript** - Type safety
- **Zod** - Runtime validation
- **Progressive Enhancement** - Works without JavaScript

See [Frontend README](./frontend/README.md) for details.

## Project Structure

```
finance-aggregator/
├── backend/                  # Clojure backend
│   ├── src/                  # Source code
│   ├── test/                 # Tests
│   ├── resources/            # Configuration and secrets
│   └── README.md             # Backend documentation
├── frontend/                 # React frontend
│   ├── app/                  # Application code
│   ├── tests/                # Tests
│   └── README.md             # Frontend documentation
├── doc/                      # Architecture documentation
│   ├── adr/                  # Architecture Decision Records
│   └── implementation/       # Implementation guides
├── scripts/                  # Development scripts
├── Procfile                  # Overmind process definitions
├── DEVELOPMENT.md            # Development setup guide
└── README.md                 # This file
```

## Documentation

### Getting Started
- [Development Setup](./DEVELOPMENT.md) - Complete development environment setup
- [Backend README](./backend/README.md) - Backend architecture and API
- [Frontend README](./frontend/README.md) - Frontend architecture and components

### Architecture Decision Records
- [ADR-001: Category Management Prototype](./doc/adr/adr-001-category-management-scittle-prototype.md)
- [ADR-002: Modern React Frontend](./doc/adr/adr-002-modern-react-frontend-architecture.md)
- [ADR-003: Clojure Backend Architecture](./doc/adr/adr-003-clojure-backend-architecture.md)
- [ADR-004: Plaid Integration](./doc/adr/adr-004-plaid-integration.md)

### Implementation Guides
- [Backend REPL Guide](./doc/implementation/adr-003-backend/repl-quick-reference.md)
- [Secrets Management](./backend/SECRETS.md)
- [Plaid Testing Guide](./backend/PLAID_TESTING.md)

## Development Workflow

### Running the Application

```bash
# Start everything (recommended)
overmind start

# Or manually in separate terminals:
# Terminal 1 - Backend
cd backend
jabba use zulu@21.0.6
clojure -M:repl -m nrepl.cmdline
# Then: (go)

# Terminal 2 - Frontend
cd frontend
pnpm run dev
```

### Running Tests

```bash
# Backend
cd backend
jabba use zulu@21.0.6
clojure -M:test -m kaocha.runner

# Frontend
cd frontend
pnpm test
```

### REPL Development (Backend)

```bash
cd backend
jabba use zulu@21.0.6
clojure -M:repl -m nrepl.cmdline
```

Then in your REPL:
```clojure
(dev)      ; Load dev environment
(go)       ; Start the system
(reset)    ; Reload and restart after code changes
(halt)     ; Stop the system
```

See [REPL Quick Reference](./doc/implementation/adr-003-backend/repl-quick-reference.md) for more commands.

## Current Status

### ✅ Completed
- Frontend with React + Remix architecture
- Backend infrastructure with Integrant
- Category management system
- Transaction table with filtering
- Secrets management with age encryption
- REPL-driven development workflow
- Plaid integration Phase 1 (client functions)

### 🚧 In Progress
- Plaid integration Phase 2 (data transformation, service layer)
- User authentication and multi-user support
- Automated transaction categorization

### 📋 Planned
- Plaid Link UI integration (Phase 5)
- Transaction sync from Plaid
- Spending analytics dashboard
- Budget tracking
- Mobile-responsive design improvements

## Technology Stack

**Backend:**
- Clojure 1.12
- Integrant (system management)
- Datalevin (database)
- HTTP-Kit (web server)
- Plaid Java SDK (financial data)

**Frontend:**
- React 19
- React Router v7
- TypeScript
- Zod (validation)
- Vite (build tool)

**Infrastructure:**
- Babashka (scripting)
- age (encryption)
- Overmind (process management)
- Jabba (Java version management)

## Contributing

This is a personal project, but the architecture and patterns may be useful for reference.

## License

Private project
