import { useCallback, useEffect, useState } from "react";
import { interviewApi } from "./api/interviewApi";
import { QuestionBank } from "./pages/QuestionBank";
import { MockInterview } from "./pages/MockInterview";
import type { AppConfig } from "./types/interview";
export default function App() {
  const [config, setConfig] = useState<AppConfig | null>(null);
  const [error, setError] = useState("");
  const load = useCallback(async () => {
    setError("");
    try {
      setConfig(await interviewApi.config());
    } catch (e) {
      setError(
        e instanceof Error ? e.message : "Could not load application mode",
      );
    }
  }, []);
  useEffect(() => {
    void load();
  }, [load]);
  return (
    <main className="app-shell">
      {error ? (
        <section className="panel">
          <p className="error" role="alert">
            {error}
          </p>
          <button className="secondary" onClick={() => void load()}>
            Retry connection
          </button>
        </section>
      ) : !config ? (
        <p role="status">Loading InterviewBot…</p>
      ) : config.mode === "interview_mode" ? (
        <MockInterview config={config} />
      ) : (
        <QuestionBank />
      )}
    </main>
  );
}
