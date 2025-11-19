# Finance Aggregator

A Clojure-based tool for aggregating and categorizing monthly transactions from multiple financial institutions.

## Features

- **SimpleFin Bridge Integration**: Pull transaction data from supported institutions via SimpleFin Bridge API
- **CSV Parser**: Import transactions from bank statement CSV files
- **PDF Parser**: Extract transaction data from PDF bank statements
- **Data Normalization**: Unified transaction schema across all data sources
- **Categorization**: Automatic transaction categorization
- **Reporting**: Monthly spending reports using Tablecloth/data science toolkit

## Supported Institutions

- Scotiabank
- Canadian Tire Bank
- Amazon MBNA Credit
- Wealthsimple
- Manulife Group Retirement Plan
- Canada Life Group Retirement
- Merix Mortgage
- Company Shares
- House (asset tracking)

## Project Structure

```
finance-aggregator/
├── src/finance_aggregator/
│   ├── core.clj              # Main entry point
│   ├── schema.clj            # Data schemas and validation
│   ├── simplefin.clj         # SimpleFin Bridge API client
│   ├── parsers/
│   │   ├── csv.clj           # CSV parser
│   │   └── pdf.clj           # PDF parser
│   ├── categorization.clj    # Transaction categorization
│   └── reporting.clj         # Reporting and analysis
├── resources/
│   └── config.edn            # Configuration
├── data/                     # Data directory (gitignored)
└── test/
```

## Setup

1. Get your SimpleFin Bridge setup token from https://bridge.simplefin.org/simplefin/create
2. Configure `resources/config.edn` with your credentials
3. Place CSV/PDF statements in the `data/` directory

## Usage

```clojure
(require '[finance-aggregator.core :as fa])

;; Fetch data from all sources
(def transactions (fa/aggregate-all))

;; Generate monthly report
(fa/monthly-report transactions "2024-01")
```

## Dependencies

- **tablecloth**: Tabular data processing
- **tick**: Date/time handling
- **malli**: Schema validation
- **clj-http**: HTTP client for SimpleFin API
- **pdfbox**: PDF parsing
