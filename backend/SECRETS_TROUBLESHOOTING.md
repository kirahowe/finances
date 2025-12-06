# Secrets Management Troubleshooting

## Common Issues

### `bb secrets edit` or `bb secrets new` hangs

**Symptoms**: The command prints "Opening editor..." but then hangs with no editor appearing.

**Cause**: The script needs to open an interactive terminal editor (vim, nano, etc.) but wasn't properly inheriting the terminal's stdin/stdout/stderr.

**Fix**: The script now uses `ProcessBuilder` with `.inheritIO()` to properly connect the editor to your terminal. This was fixed in commit [date].

**Workaround** (if you still have issues):
```bash
# Manually decrypt, edit, and re-encrypt
bb secrets decrypt backend/resources/secrets.edn.age
vim backend/resources/secrets.edn
bb secrets encrypt backend/resources/secrets.edn
rm backend/resources/secrets.edn  # Clean up plaintext
```

### No editor defined

**Symptoms**: Error about missing editor

**Solution**: Set your preferred editor:
```bash
# In your shell config (~/.zshrc or ~/.bashrc)
export VISUAL=vim          # or nano, emacs, code --wait, etc.
export EDITOR=vim
```

### Editor exits immediately

**Symptoms**: Editor opens and immediately closes

**Possible causes**:
1. **GUI editor without wait flag**: If using VS Code, Sublime, etc., add the wait flag:
   ```bash
   export VISUAL="code --wait"
   export VISUAL="subl --wait"
   ```

2. **File permissions**: Check that the temp file is readable:
   ```bash
   ls -la /tmp/secrets-*
   ```

### Key file not found

**Symptoms**: `Age identity file (private key) not found`

**Solution**:
```bash
bb secrets keygen
```

This creates `~/.config/finance-aggregator/key.txt`

### Encrypted file not found (for edit command)

**Symptoms**: `Encrypted secrets file not found: backend/resources/secrets.edn.age`

**Solution**: Create it first:
```bash
bb secrets new
```

The `edit` command requires an existing encrypted file. Use `new` to create one from the template.

## Workflow Summary

First time setup:
```bash
bb secrets keygen    # Generate encryption key
bb secrets new       # Create encrypted secrets file (opens editor)
```

Daily usage:
```bash
bb secrets edit      # Edit existing secrets (opens editor)
```

## Technical Details

### Why ProcessBuilder?

The original implementation used `clojure.java.shell/sh` which:
- Captures stdin/stdout/stderr
- Doesn't allow interactive terminal programs
- Returns after the process completes

The fix uses Java's `ProcessBuilder` with `.inheritIO()` which:
- Passes through stdin/stdout/stderr to the terminal
- Allows interactive programs like vim/nano
- Lets the editor fully control the terminal

### Code Example

```clojure
;; Before (doesn't work for interactive editors)
(shell/sh editor temp-file)

;; After (works for interactive editors)
(let [pb (ProcessBuilder. (into-array String [editor temp-file]))
      _ (.inheritIO pb)
      process (.start pb)
      exit-code (.waitFor process)]
  (when-not (zero? exit-code)
    (throw (ex-info "Editor failed" {:exit-code exit-code}))))
```

## Getting Help

If you continue to have issues:

1. Check your editor works independently:
   ```bash
   echo $VISUAL
   $VISUAL /tmp/test.txt
   ```

2. Verify age is installed:
   ```bash
   age --version
   ```

3. Test the ProcessBuilder directly:
   ```bash
   bb -e '(let [pb (ProcessBuilder. (into-array String ["vim" "/tmp/test.txt"])) _ (.inheritIO pb) p (.start pb)] (.waitFor p))'
   ```

4. Check the full error output:
   ```bash
   bb secrets new 2>&1 | tee debug.log
   ```
