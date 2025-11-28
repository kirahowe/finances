# ADR-003 Backend Implementation Documentation

This directory contains documentation for the Clojure backend architecture as defined in [ADR-003: Clojure Backend Architecture](../../adr/adr-003-clojure-backend-architecture.md).

## Files in This Directory

### Ongoing References (Living Documents)

- **[repl-quick-reference.md](./repl-quick-reference.md)**
  - Quick reference for REPL commands and common tasks
  - Updated as new features are added
  - Keep this handy while developing

### Point-in-Time Snapshots

- **[phase1-implementation-complete-2025-11-25.md](./phase1-implementation-complete-2025-11-25.md)**
  - Complete documentation of Phase 1 (Infrastructure Layer)
  - What was implemented, how it works, and next steps
  - Snapshot as of November 25, 2025

- **[dev-environment-setup-2025-11-25.md](./dev-environment-setup-2025-11-25.md)**
  - Documentation of dev environment improvements
  - REPL experience enhancements and tooling
  - Snapshot as of November 25, 2025

## Naming Convention

Files are named to clearly indicate:
1. **Which ADR** they reference (implicit from directory structure: `adr-003-backend/`)
2. **Purpose** (e.g., `repl-quick-reference`, `phase1-implementation-complete`)
3. **Time validity** (dated files are point-in-time snapshots, undated are living documents)

### Examples:
- `repl-quick-reference.md` - Living document, updated as needed
- `phase1-implementation-complete-2025-11-25.md` - Snapshot of Phase 1 completion

## Implementation Phases

### Phase 1: Infrastructure Layer ✅ (Completed 2025-11-25)
- System lifecycle management with Integrant
- Enhanced database schema with user scoping
- HTTP server component
- Dev environment with REPL tooling

See: [phase1-implementation-complete-2025-11-25.md](./phase1-implementation-complete-2025-11-25.md)

### Phase 2: Data Access Layer (Next)
- User-scoped queries (`db/queries.clj`)
- User-scoped transactions (`db/transactions.clj`)
- Domain layer with pure functions
- Credential encryption

### Phase 3: Application Layer (Planned)
- Reitit routing
- API handlers
- Request validation with Malli
- Authentication middleware

## Related Documentation

- **ADR-003**: [../../adr/adr-003-clojure-backend-architecture.md](../../adr/adr-003-clojure-backend-architecture.md)
- **REPL Cheat Sheet**: [../../../backend/REPL-CHEATSHEET.md](../../../backend/REPL-CHEATSHEET.md)

## Quick Start

For day-to-day development, refer to:
1. **[repl-quick-reference.md](./repl-quick-reference.md)** - Common commands
2. **[../../../backend/REPL-CHEATSHEET.md](../../../backend/REPL-CHEATSHEET.md)** - Print-friendly reference

For understanding the architecture:
1. Start with **[ADR-003](../../adr/adr-003-clojure-backend-architecture.md)**
2. Then read **[phase1-implementation-complete-2025-11-25.md](./phase1-implementation-complete-2025-11-25.md)**
