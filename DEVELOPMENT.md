# Development Setup

Quick guide to running the Finance Aggregator locally.

## Prerequisites

### Required Tools

1. **Babashka** (for secrets management):
   ```bash
   brew install borkdude/brew/babashka
   ```

2. **age encryption** (for secrets):
   ```bash
   brew install age
   ```

3. **Jabba** (Java version manager):
   ```bash
   curl -sL https://github.com/shyiko/jabba/raw/master/install.sh | bash
   jabba install zulu@21.0.6
   ```

4. **Overmind** (process manager):
   ```bash
   brew install overmind
   ```

5. **pnpm** (package manager):
   ```bash
   brew install pnpm
   ```

## First-Time Setup

### 1. Install Frontend Dependencies

```bash
cd frontend && pnpm install && cd ..
```

### 2. Configure Secrets

The project uses age-encrypted secrets instead of environment variables. No `.env` files needed!

```bash
# Generate your encryption key
bb secrets keygen

# Create and edit your secrets file
bb secrets new
```

In your editor, add your Plaid API credentials and a database encryption key:

```clojure
{:plaid {:client-id "your_actual_client_id"
         :secret "your_actual_secret"
         :environment :sandbox}

 :database {:encryption-key "GENERATE_ME"}}  ; See below to generate
```

**To generate a secure encryption key:**
```bash
# Start a Clojure REPL
cd backend && clojure -M:repl

# Generate and copy the key
(require '[finance-aggregator.lib.encryption :as enc])
(println (enc/generate-encryption-key))
# Copy the output and paste it as your :encryption-key value
```

Get your Plaid credentials from: https://dashboard.plaid.com/team/keys

See `backend/SECRETS.md` for detailed documentation.

## Running the Application

### Start Everything with Overmind

```bash
overmind start
```

This starts:
- **Frontend** (web): http://localhost:5173
- **Backend API** (api): http://localhost:8080

### Stop All Processes

```bash
# Ctrl+C in the overmind terminal, or:
overmind quit
```

### Connect to Individual Processes

```bash
# See all processes
overmind status

# Connect to backend logs
overmind connect api

# Connect to frontend logs
overmind connect web
```

## Alternative: Manual Start

If you prefer to run processes separately:

```bash
# Terminal 1 - Backend REPL (recommended for development)
cd backend
jabba use zulu@21.0.6
clojure -M:repl -m nrepl.cmdline
# Then in REPL: (go)

# Or run directly without REPL
clojure -M:dev -m finance-aggregator.main

# Terminal 2 - Frontend
cd frontend
pnpm run dev
```

## Testing

### Backend Tests

```bash
cd backend
jabba use zulu@21.0.6
clojure -M:test -m kaocha.runner
```

### Frontend Tests

```bash
cd frontend
pnpm test
```

## REPL Development (Backend)

For interactive development with the backend:

```bash
cd backend
jabba use zulu@21.0.6
clojure -M:repl -m nrepl.cmdline
```

Then in your editor, connect to the REPL and run:

```clojure
(require 'user)
(help)   ; See all available REPL commands
(dev)    ; Load dev namespace
(go)     ; Start the system
```

See `doc/implementation/adr-003-backend/repl-quick-reference.md` for details.

## Project Structure

```
finance-aggregator/
├── Procfile              # Overmind process definitions
├── backend/              # Clojure backend
│   ├── src/              # Source code
│   ├── test/             # Tests
│   ├── resources/        # Config and secrets
│   └── SECRETS.md        # Secrets management guide
├── frontend/             # React frontend
│   └── src/              # Frontend code
└── scripts/
    └── secrets.clj       # bb secrets command
```

## Key Services

- **Frontend**: http://localhost:5173
- **Backend API**: http://localhost:8080
- **Health Check**: http://localhost:8080/health
- **Database**: Embedded Datalevin at `backend/data/finance.db`

## Troubleshooting

### "bb: command not found"

Install Babashka: `brew install borkdude/brew/babashka`

### "age: command not found"

Install age: `brew install age`

### "overmind: command not found"

Install Overmind: `brew install overmind`

### "Failed to decrypt secrets file"

You need to generate an age key and create secrets:

```bash
bb secrets keygen
bb secrets new
```

### Backend won't start

Make sure you've set the Java version:

```bash
cd backend
jabba use zulu@21.0.6
```

## Documentation

- **Backend**:
  - Architecture: `doc/adr/adr-003-clojure-backend-architecture.md`
  - Plaid Integration: `doc/adr/adr-004-plaid-integration.md`
  - REPL Guide: `doc/implementation/adr-003-backend/repl-quick-reference.md`
  - Secrets Management: `backend/SECRETS.md`
- **Frontend**:
  - Architecture: `doc/adr/adr-002-modern-react-frontend-architecture.md`
