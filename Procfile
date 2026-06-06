api: bash -c "cd backend && clojure -M:dev -m finance-aggregator.main"
web: bash -c "echo 'Waiting for API...' && until curl -sf http://localhost:8080/api/stats > /dev/null 2>&1; do sleep 1; done && echo 'API ready!' && cd frontend && pnpm run dev"
