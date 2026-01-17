import { useState } from "react";
import type { CsvMapping, CsvPreview, CsvImportResult } from "../lib/api";
import { api } from "../lib/api";

interface CsvImportWizardProps {
  accountId: number;
  accountName: string;
  onClose: () => void;
  onSuccess?: () => void;
}

type Step = "upload" | "map" | "preview" | "complete";

const DATE_FORMAT_OPTIONS = [
  { value: "yyyy-MM-dd", label: "YYYY-MM-DD (e.g., 2024-01-15)" },
  { value: "MM/dd/yyyy", label: "MM/DD/YYYY (e.g., 01/15/2024)" },
  { value: "dd/MM/yyyy", label: "DD/MM/YYYY (e.g., 15/01/2024)" },
  { value: "yyyy/MM/dd", label: "YYYY/MM/DD (e.g., 2024/01/15)" },
  { value: "M/d/yyyy", label: "M/D/YYYY (e.g., 1/15/2024)" },
  { value: "d/M/yyyy", label: "D/M/YYYY (e.g., 15/1/2024)" },
];

export function CsvImportWizard({
  accountId,
  accountName,
  onClose,
  onSuccess,
}: CsvImportWizardProps) {
  const [step, setStep] = useState<Step>("upload");
  const [csvContent, setCsvContent] = useState<string>("");
  const [preview, setPreview] = useState<CsvPreview | null>(null);
  const [mapping, setMapping] = useState<CsvMapping | null>(null);
  const [dateFormat, setDateFormat] = useState<string>("");
  const [isLoading, setIsLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [importResult, setImportResult] = useState<CsvImportResult | null>(null);

  const handleFileUpload = async (e: React.ChangeEvent<HTMLInputElement>) => {
    const file = e.target.files?.[0];
    if (!file) return;

    setIsLoading(true);
    setError(null);

    try {
      const text = await file.text();
      setCsvContent(text);

      const previewData = await api.previewCsv(accountId, text);
      setPreview(previewData);

      // Use stored mapping if available, otherwise use detected mapping
      if (previewData["stored-mapping"]) {
        setMapping(previewData["stored-mapping"]);
        setDateFormat(previewData["stored-mapping"]["date-format"]);
      } else if (previewData["detected-mapping"]) {
        const detected = previewData["detected-mapping"];
        setMapping({
          columns: {
            date: detected.date || "",
            amount: detected.amount || "",
            payee: detected.payee || "",
            description: detected.description,
          },
          "date-format": previewData["suggested-date-format"] || "yyyy-MM-dd",
        });
        setDateFormat(previewData["suggested-date-format"] || "yyyy-MM-dd");
      }

      setStep("map");
    } catch (err) {
      setError(err instanceof Error ? err.message : "Failed to preview CSV");
    } finally {
      setIsLoading(false);
    }
  };

  const handleMapping = (field: string, value: string) => {
    setMapping((prev) =>
      prev
        ? {
            ...prev,
            columns: { ...prev.columns, [field]: value },
          }
        : null
    );
  };

  const handleDateFormatChange = (format: string) => {
    setDateFormat(format);
    setMapping((prev) =>
      prev
        ? {
            ...prev,
            "date-format": format,
          }
        : null
    );
  };

  const handleSaveMapping = async () => {
    if (!mapping) return;

    setIsLoading(true);
    setError(null);

    try {
      await api.saveCsvMapping(accountId, mapping);
      setStep("preview");
    } catch (err) {
      setError(err instanceof Error ? err.message : "Failed to save mapping");
    } finally {
      setIsLoading(false);
    }
  };

  const handleImport = async () => {
    if (!mapping || !csvContent) return;

    setIsLoading(true);
    setError(null);

    try {
      const result = await api.importCsv(accountId, csvContent, mapping);
      setImportResult(result);
      setStep("complete");
      onSuccess?.();
    } catch (err) {
      setError(err instanceof Error ? err.message : "Failed to import CSV");
    } finally {
      setIsLoading(false);
    }
  };

  const isMapValid =
    mapping &&
    mapping.columns.date &&
    mapping.columns.amount &&
    mapping.columns.payee &&
    mapping["date-format"];

  return (
    <div className="modal-backdrop" onClick={onClose}>
      <div className="modal-content csv-wizard" onClick={(e) => e.stopPropagation()}>
        <div className="wizard-header">
          <h2>Import CSV for {accountName}</h2>
          <div className="wizard-steps">
            <div className={`wizard-step ${step === "upload" ? "active" : ""} ${step !== "upload" ? "completed" : ""}`}>
              1. Upload
            </div>
            <div className={`wizard-step ${step === "map" ? "active" : ""} ${step === "preview" || step === "complete" ? "completed" : ""}`}>
              2. Map Columns
            </div>
            <div className={`wizard-step ${step === "preview" ? "active" : ""} ${step === "complete" ? "completed" : ""}`}>
              3. Preview
            </div>
            <div className={`wizard-step ${step === "complete" ? "active" : ""}`}>
              4. Complete
            </div>
          </div>
        </div>

        {error && <div className="error-banner">{error}</div>}

        {step === "upload" && (
          <div className="wizard-content">
            <p>Select a CSV file to import transactions.</p>
            <input
              type="file"
              accept=".csv"
              onChange={handleFileUpload}
              disabled={isLoading}
              className="file-input"
            />
            {isLoading && <p>Loading preview...</p>}
          </div>
        )}

        {step === "map" && preview && mapping && (
          <div className="wizard-content">
            <p>Map CSV columns to transaction fields:</p>

            <div className="mapping-grid">
              <div className="mapping-row">
                <label className="mapping-label">Date Column:</label>
                <select
                  className="form-select"
                  value={mapping.columns.date}
                  onChange={(e) => handleMapping("date", e.target.value)}
                >
                  <option value="">Select column...</option>
                  {preview.headers.map((header) => (
                    <option key={header} value={header}>
                      {header}
                    </option>
                  ))}
                </select>
              </div>

              <div className="mapping-row">
                <label className="mapping-label">Amount Column:</label>
                <select
                  className="form-select"
                  value={mapping.columns.amount}
                  onChange={(e) => handleMapping("amount", e.target.value)}
                >
                  <option value="">Select column...</option>
                  {preview.headers.map((header) => (
                    <option key={header} value={header}>
                      {header}
                    </option>
                  ))}
                </select>
              </div>

              <div className="mapping-row">
                <label className="mapping-label">Payee Column:</label>
                <select
                  className="form-select"
                  value={mapping.columns.payee}
                  onChange={(e) => handleMapping("payee", e.target.value)}
                >
                  <option value="">Select column...</option>
                  {preview.headers.map((header) => (
                    <option key={header} value={header}>
                      {header}
                    </option>
                  ))}
                </select>
              </div>

              <div className="mapping-row">
                <label className="mapping-label">Description Column (optional):</label>
                <select
                  className="form-select"
                  value={mapping.columns.description || ""}
                  onChange={(e) => handleMapping("description", e.target.value)}
                >
                  <option value="">Select column...</option>
                  {preview.headers.map((header) => (
                    <option key={header} value={header}>
                      {header}
                    </option>
                  ))}
                </select>
              </div>

              <div className="mapping-row">
                <label className="mapping-label">Date Format:</label>
                <select
                  className="form-select"
                  value={dateFormat}
                  onChange={(e) => handleDateFormatChange(e.target.value)}
                >
                  {DATE_FORMAT_OPTIONS.map((option) => (
                    <option key={option.value} value={option.value}>
                      {option.label}
                    </option>
                  ))}
                </select>
              </div>
            </div>

            <div className="sample-data">
              <h3>Sample Data:</h3>
              <div className="table-container">
                <table className="table">
                  <thead>
                    <tr>
                      {preview.headers.map((header) => (
                        <th key={header}>{header}</th>
                      ))}
                    </tr>
                  </thead>
                  <tbody>
                    {preview["sample-rows"].map((row, idx) => (
                      <tr key={idx}>
                        {preview.headers.map((header) => (
                          <td key={header}>{row[header]}</td>
                        ))}
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            </div>

            <div className="wizard-actions">
              <button className="button button-secondary" onClick={onClose}>
                Cancel
              </button>
              <button
                className="button"
                onClick={handleSaveMapping}
                disabled={!isMapValid || isLoading}
              >
                {isLoading ? "Saving..." : "Continue to Preview"}
              </button>
            </div>
          </div>
        )}

        {step === "preview" && preview && mapping && (
          <div className="wizard-content">
            <div className="preview-summary">
              <h3>Ready to Import</h3>
              <p>
                <strong>{preview["total-rows"]}</strong> transactions will be imported
              </p>
              <p className="preview-note">
                Transactions with the same date, amount, and payee will be updated instead of duplicated.
              </p>
            </div>

            <div className="wizard-actions">
              <button className="button button-secondary" onClick={() => setStep("map")}>
                Back
              </button>
              <button
                className="button"
                onClick={handleImport}
                disabled={isLoading}
              >
                {isLoading ? "Importing..." : "Import Transactions"}
              </button>
            </div>
          </div>
        )}

        {step === "complete" && importResult && (
          <div className="wizard-content">
            <div className="success-message">
              <h3>Import Complete!</h3>
              <p>
                <strong>{importResult.imported}</strong> transactions imported
              </p>
              {importResult["skipped-duplicates"] > 0 && (
                <p className="skipped-info">
                  {importResult["skipped-duplicates"]} duplicates skipped
                </p>
              )}
              {importResult.errors.length > 0 && (
                <div className="error-list">
                  <h4>Errors:</h4>
                  <ul>
                    {importResult.errors.map((error, idx) => (
                      <li key={idx}>{error}</li>
                    ))}
                  </ul>
                </div>
              )}
            </div>

            <div className="wizard-actions">
              <button className="button" onClick={onClose}>
                Close
              </button>
            </div>
          </div>
        )}
      </div>
    </div>
  );
}
