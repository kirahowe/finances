# Secrets Management

Finance Aggregator uses [age encryption](https://github.com/FiloSottile/age) for secure secrets management. Secrets are stored in encrypted `.edn.age` files that can be safely committed to git.

## Quick Start

### 1. Install Prerequisites

**age encryption:**
```bash
# macOS
brew install age

# Linux
apt install age  # Debian/Ubuntu
```

**Babashka (for management scripts):**
```bash
# macOS
brew install borkdude/brew/babashka

# Linux
curl -s https://raw.githubusercontent.com/babashka/babashka/master/install | bash
```

### 2. Generate Your Encryption Key

```bash
# Generate with bb secrets (recommended)
bb secrets keygen

# Or manually
age-keygen -o ~/.config/finance-aggregator/key.txt
```

**Important:** Back up this key file securely! You'll need it to decrypt secrets. Without it, encrypted secrets cannot be recovered.

### 3. Create Secrets File

```bash
bb secrets new
```

This will:
1. Create a secrets file from the template
2. Open your editor (set `$EDITOR` or `$VISUAL`)
3. Encrypt the file when you save and exit
4. Securely delete the plaintext

### 4. Edit Your Secrets

Add your actual credentials (e.g., Plaid API keys):

```clojure
{:plaid {:client-id "your_actual_client_id"
         :secret "your_actual_secret"
         :environment :sandbox}

 :database {:encryption-key nil}}
```

## Usage

### Managing Secrets

```bash
# Edit existing secrets
bb secrets edit

# Show your public key (for team sharing)
bb secrets show-key

# Encrypt any file
bb secrets encrypt secret-file.edn

# Decrypt any file
bb secrets decrypt secret-file.edn.age

# Show help
bb secrets help
```

### Loading Secrets in Code

```clojure
(require '[finance-aggregator.lib.secrets :as secrets])

;; Load all secrets (uses convention: ~/.config/finance-aggregator/key.txt)
(def secrets (secrets/load-secrets))

;; Get Plaid configuration
(secrets/get-secret secrets :plaid)
;; => {:client-id "...", :secret "..., :environment :sandbox}

;; Get nested value
(secrets/get-secret secrets [:plaid :client-id])
;; => "your_client_id"

;; Load with custom paths (if needed)
(secrets/load-secrets "~/my-key.txt" "path/to/secrets.edn.age")
```

### Configuration

By convention, secrets use these locations:
- **Key file:** `~/.config/finance-aggregator/key.txt`
- **Secrets file:** `backend/resources/secrets.edn.age`
- **Editor:** `$VISUAL`, `$EDITOR`, or `vim` (in that order)

No environment variables required! The system uses sensible defaults.

## Team Collaboration

### Adding a Team Member

When a new team member joins:

1. **New member generates their key:**
   ```bash
   age-keygen -o ~/.config/finance-aggregator/key.txt
   ./scripts/secrets show-key
   ```

2. **They share their public key** (looks like: `age1ql3z7hjy54pw3hyww5ayyfg7zqgvc7w3j2elw8zmrj2kg5sfn9aqmcac8p`)

3. **Existing member re-encrypts secrets** for multiple recipients:
   ```bash
   # Decrypt to temp file
   ./scripts/secrets decrypt backend/resources/secrets.edn.age

   # Re-encrypt for multiple recipients
   age -e -i ~/.config/finance-aggregator/key.txt \
       -r age1ql3z7hjy54pw3hyww5ayyfg7zqgvc7w3j2elw8zmrj2kg5sfn9aqmcac8p \
       -o backend/resources/secrets.edn.age \
       backend/resources/secrets.edn.decrypted

   # Securely delete plaintext
   rm -P backend/resources/secrets.edn.decrypted  # macOS
   # or
   shred -u backend/resources/secrets.edn.decrypted  # Linux

   # Commit and push
   git add backend/resources/secrets.edn.age
   git commit -m "Add new team member to secrets"
   git push
   ```

4. **New member pulls and verifies:**
   ```bash
   git pull
   ./scripts/secrets edit  # Should decrypt successfully
   ```

## Integration with Backend System

Secrets are loaded automatically at system startup through Integrant configuration. **No environment variables needed!**

### How It Works

**Configuration (in `resources/system/base-system.edn`):**
```clojure
{;; Secrets configuration (convention over configuration)
 :finance-aggregator.system/secrets-key-file "~/.config/finance-aggregator/key.txt"
 :finance-aggregator.system/secrets-file "resources/secrets.edn.age"

 ;; Secrets component - loads encrypted secrets at startup
 :finance-aggregator.system/secrets
 {:key-file #ig/ref :finance-aggregator.system/secrets-key-file
  :secrets-file #ig/ref :finance-aggregator.system/secrets-file}

 ;; Plaid config - loaded from secrets (no env vars!)
 :finance-aggregator.plaid/config
 {:secrets #ig/ref :finance-aggregator.system/secrets}}
```

**Component Implementation (in `src/finance_aggregator/system.clj`):**
```clojure
(require '[finance-aggregator.lib.secrets :as secrets])

(defmethod ig/init-key :finance-aggregator.system/secrets
  [_ {:keys [key-file secrets-file]}]
  (println "Loading encrypted secrets from" secrets-file)
  (secrets/load-secrets key-file secrets-file))

(defmethod ig/init-key :finance-aggregator.plaid/config
  [_ {:keys [secrets]}]
  (if-let [plaid-config (secrets/get-secret secrets :plaid)]
    plaid-config
    (throw (ex-info "Plaid credentials not found in secrets"
                    {:hint "Run 'bb secrets edit' to add Plaid credentials"}))))
```

### Overriding for Different Environments

To use different key files per environment, override in environment-specific config files:

**For production (`resources/system/prod.edn`):**
```clojure
{;; Override secrets paths for production
 :finance-aggregator.system/secrets-key-file "~/.config/finance-aggregator/prod-key.txt"
 :finance-aggregator.system/secrets-file "resources/prod-secrets.edn.age"}
```

**For CI/CD (`resources/system/ci.edn`):**
```clojure
{;; Override for CI environment
 :finance-aggregator.system/secrets-key-file "/ci/secrets/key.txt"
 :finance-aggregator.system/secrets-file "resources/ci-secrets.edn.age"}
```

The system uses Integrant's configuration merging to override values per environment. See Integrant documentation for more details on configuration merging.

## Security Best Practices

### DO

✅ Commit encrypted `.edn.age` files to git
✅ Commit `.edn.example` template files
✅ Back up your age key file securely (password manager, encrypted backup)
✅ Use different secrets files for dev/staging/production
✅ Rotate secrets regularly
✅ Use `secrets.edn.age` naming convention (clear that it's encrypted)

### DON'T

❌ Never commit plaintext `.edn` secrets files
❌ Never commit age key files (`.txt` or `.key`)
❌ Never share secrets via Slack, email, or other insecure channels
❌ Don't lose your age key (you can't recover secrets without it)
❌ Don't use the same key for multiple projects
❌ Don't put secrets in environment variables for production (use encrypted files)

## File Locations

```
finance-aggregator/
├── bb.edn                                   # Babashka task definitions
├── scripts/
│   └── secrets.clj                          # Babashka management script
├── backend/
│   ├── resources/
│   │   ├── secrets.edn.example              # Template (committed)
│   │   ├── secrets.edn.age                  # Encrypted secrets (committed)
│   │   └── secrets.edn                      # Plaintext (NEVER commit, in .gitignore)
│   ├── src/finance_aggregator/lib/
│   │   └── secrets.clj                      # Runtime loading library
│   └── test/finance_aggregator/lib/
│       └── secrets_test.clj                 # Comprehensive test suite
└── ~/.config/finance-aggregator/
    └── key.txt                              # Your age key (NEVER commit)
```

## Troubleshooting

### "bb: No such file or directory"

Install Babashka:
```bash
brew install borkdude/brew/babashka
```

### "age encryption tool is not installed"

Install age:
```bash
# macOS
brew install age

# Linux
apt install age
```

### "Age identity file (private key) not found"

Generate a key:
```bash
mkdir -p ~/.config/finance-aggregator
age-keygen -o ~/.config/finance-aggregator/key.txt
```

### "Failed to decrypt secrets file"

Possible causes:
1. **You're not a recipient** - Ask a team member to re-encrypt with your public key
2. **Wrong key file** - Check `AGE_IDENTITY_FILE` environment variable
3. **Corrupted file** - Restore from git history

### "Encrypted secrets file not found"

Create secrets:
```bash
./scripts/secrets new
```

## Migration from Environment Variables

If you're currently using `.env` files or environment variables:

1. **Create secrets file:**
   ```bash
   ./scripts/secrets new
   ```

2. **Copy values from `.env` to secrets.edn:**
   ```clojure
   ;; .env
   PLAID_CLIENT_ID=abc123
   PLAID_SECRET=secret456

   ;; secrets.edn
   {:plaid {:client-id "abc123"
            :secret "secret456"
            :environment :sandbox}}
   ```

3. **Update code to use secrets library:**
   ```clojure
   ;; Before
   (def client-id (System/getenv "PLAID_CLIENT_ID"))

   ;; After
   (def secrets (secrets/load-secrets))
   (def client-id (secrets/get-secret secrets [:plaid :client-id]))
   ```

4. **Remove from `.env`:**
   ```bash
   # Keep .env.example for reference, but remove actual secrets from .env
   ```

## Advanced Usage

### Multiple Environments

```bash
# Development secrets (uses default key)
bb secrets edit

# Production secrets (use a separate key file!)
bb secrets keygen ~/.config/finance-aggregator/prod-key.txt
# Then manually encrypt with the prod key:
age -e -i ~/.config/finance-aggregator/prod-key.txt \
    -o backend/resources/prod-secrets.edn.age \
    backend/resources/prod-secrets.edn
```

### Loading Production Secrets in Code

```clojure
;; Dev (default)
(def dev-secrets (secrets/load-secrets))

;; Production (explicit paths)
(def prod-secrets
  (secrets/load-secrets
    "~/.config/finance-aggregator/prod-key.txt"
    "resources/prod-secrets.edn.age"))
```

### Automated Decryption in CI/CD

```bash
# Store age key as CI secret, then decrypt in pipeline
mkdir -p ~/.config/finance-aggregator
echo "$AGE_KEY" > ~/.config/finance-aggregator/key.txt
chmod 600 ~/.config/finance-aggregator/key.txt
# Now your code can load secrets normally with (load-secrets)
```

### Key Rotation

When rotating your age key:

1. Generate new key: `age-keygen -o new-key.txt`
2. Decrypt with old key: `age -d -i old-key.txt secrets.edn.age > secrets.edn`
3. Re-encrypt with new key: `age -e -i new-key.txt -o secrets.edn.age secrets.edn`
4. Securely delete old key and plaintext
5. Update `AGE_IDENTITY_FILE` or move new key to default location

## Additional Resources

- [age encryption](https://github.com/FiloSottile/age) - Modern, simple file encryption
- [Babashka](https://github.com/babashka/babashka) - Fast native Clojure scripting
- [Integrant](https://github.com/weavejester/integrant) - Component lifecycle management
- Reference implementation: `~/code/seeq/seeqai/backend/src/lib/secrets.clj`
