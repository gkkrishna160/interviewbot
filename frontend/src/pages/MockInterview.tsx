import { useRef, useState } from "react";
import { interviewApi } from "../api/interviewApi";
import type {
  AppConfig,
  Area,
  PracticeQuestion,
  PracticeResult,
} from "../types/interview";

type Slot = { area: Area; question?: PracticeQuestion };
export function MockInterview({ config }: { config: AppConfig }) {
  const [areas, setAreas] = useState<Area[]>([]);
  const [queue, setQueue] = useState<Slot[]>([]);
  const [index, setIndex] = useState(0);
  const [results, setResults] = useState<PracticeResult[]>([]);
  const [answer, setAnswer] = useState("");
  const [feedback, setFeedback] = useState<PracticeResult | null>(null);
  const [loading, setLoading] = useState("");
  const [error, setError] = useState("");
  const [started, setStarted] = useState(false);
  const [completed, setCompleted] = useState(false);
  const busy = useRef(false);
  const areaName = (id: Area) =>
    config.areas.find((a) => a.id === id)?.name ?? id;
  async function prepare(
    slots: Slot[],
    position: number,
    previous: PracticeQuestion[],
  ) {
    const slot = slots[position];
    if (slot && !slot.question) {
      setLoading("The interview agent is preparing your next question…");
      const question = await interviewApi.next(
        slot.area,
        previous
          .filter((q) => q.question.area === slot.area)
          .map((q) => q.token),
      );
      slots = slots.map((s, i) => (i === position ? { ...s, question } : s));
    }
    setQueue(slots);
  }
  async function start() {
    if (busy.current || !areas.length) return;
    busy.current = true;
    setLoading("Selecting your questions…");
    setError("");
    try {
      const selected = await interviewApi.questions(areas);
      const slots: Slot[] = selected.questions.map((q) => ({
        area: q.question.area,
        question: q,
      }));
      selected.shortfalls.forEach((gap) => {
        for (let i = 0; i < gap.count; i++) slots.push({ area: gap.area });
      });
      setQueue(slots);
      setIndex(0);
      setResults([]);
      setFeedback(null);
      setAnswer("");
      setCompleted(false);
      setStarted(true);
      await prepare(slots, 0, selected.questions);
    } catch (e) {
      setError(e instanceof Error ? e.message : "Could not start practice");
    } finally {
      setLoading("");
      busy.current = false;
    }
  }
  async function submit() {
    const current = queue[index]?.question;
    if (busy.current || !current || !answer.trim()) return;
    busy.current = true;
    setLoading("The interview judge is reviewing your answer…");
    setError("");
    try {
      const judgment = await interviewApi.assess(current.token, answer);
      const result = { question: current, answer, judgment };
      setResults((r) => [...r, result]);
      setFeedback(result);
    } catch (e) {
      setError(e instanceof Error ? e.message : "Could not assess your answer");
    } finally {
      setLoading("");
      busy.current = false;
    }
  }
  async function next() {
    if (busy.current) return;
    if (index + 1 >= queue.length) {
      setCompleted(true);
      setFeedback(null);
      return;
    }
    busy.current = true;
    setError("");
    try {
      await prepare(
        queue,
        index + 1,
        queue.flatMap((s) => (s.question ? [s.question] : [])),
      );
      setIndex((i) => i + 1);
      setAnswer("");
      setFeedback(null);
    } catch (e) {
      setError(
        e instanceof Error ? e.message : "Could not prepare the next question",
      );
    } finally {
      setLoading("");
      busy.current = false;
    }
  }
  const current = queue[index]?.question;
  const average = results.length
    ? results.reduce((n, r) => n + r.judgment.score, 0) / results.length
    : 0;
  return (
    <section className="practice-page">
      <header className="practice-header">
        <div>
          <p className="eyebrow">Interview practice</p>
          <h1>Learn with every answer.</h1>
          <p>
            Answer essential interview questions and get clear feedback, a
            score, and a reference answer.
          </p>
        </div>
        <span className="mode-label">Interview mode</span>
      </header>
      {!started ? (
        <>
          <h2>What would you like to practice?</h2>
          <p>
            Select one area or combine them. You’ll answer ten questions from
            each selected area.
          </p>
          <div className="practice-areas">
            {config.areas.map((area) => (
              <label
                className={`practice-area ${areas.includes(area.id) ? "chosen" : ""}`}
                key={area.id}
              >
                <input
                  type="checkbox"
                  checked={areas.includes(area.id)}
                  disabled={Boolean(loading)}
                  onChange={() =>
                    setAreas((a) =>
                      a.includes(area.id)
                        ? a.filter((id) => id !== area.id)
                        : [...a, area.id],
                    )
                  }
                />
                <strong>{area.name}</strong>
                <span>10 questions</span>
                <small>
                  {Math.min(10, area.approved)} available from the bank
                  {area.approved < 10
                    ? " · the interview agent supplies the rest"
                    : ""}
                </small>
              </label>
            ))}
          </div>
          <button
            className="primary"
            disabled={!areas.length || Boolean(loading)}
            onClick={() => void start()}
          >
            {loading ||
              `Start practice${areas.length ? ` · ${areas.length * 10} questions` : ""}`}
          </button>
        </>
      ) : completed ? (
        <>
          <section className="panel practice-summary">
            <p className="eyebrow">Your practice review</p>
            <h2>
              {average.toFixed(1)} <span>/ 10</span>
            </h2>
            <p>
              {results.length} questions answered. Use the feedback below to
              guide what you study next.
            </p>
            {areas.map((area) => {
              const group = results.filter(
                (r) => r.question.question.area === area,
              );
              return (
                <div className="score-row" key={area}>
                  <span>{areaName(area)}</span>
                  <strong>
                    {(
                      group.reduce((n, r) => n + r.judgment.score, 0) /
                      (group.length || 1)
                    ).toFixed(1)}{" "}
                    / 10
                  </strong>
                </div>
              );
            })}
          </section>
          {results.map((r, i) => (
            <details
              className="panel practice-history"
              key={r.question.question.id}
            >
              <summary>
                {i + 1}. {r.question.question.text}{" "}
                <strong>{r.judgment.score}/10</strong>
              </summary>
              <h3>Your answer</h3>
              <p className="answer-copy">{r.answer}</p>
              <h3>Feedback</h3>
              <p className="answer-copy">{r.judgment.feedback}</p>
              <h3>Reference answer</h3>
              <p className="answer-copy">{r.judgment.modelAnswer}</p>
            </details>
          ))}
          <button
            className="primary"
            onClick={() => {
              setStarted(false);
              setCompleted(false);
              setResults([]);
              setQueue([]);
              setError("");
            }}
          >
            Start another practice
          </button>
        </>
      ) : (
        <>
          <div className="practice-progress">
            <span>
              Question {index + 1} of {queue.length}
            </span>
            <span>{results.length} reviewed</span>
          </div>
          <progress
            value={index}
            max={queue.length}
            aria-label="Interview progress"
          />
          {feedback ? (
            <section className="panel practice-feedback">
              <p className="eyebrow">
                Question {index + 1} ·{" "}
                {areaName(feedback.question.question.area)}
              </p>
              <h2>{feedback.question.question.text}</h2>
              <div className="practice-score">
                {feedback.judgment.score}
                <span>/10</span>
              </div>
              <h3>Your answer</h3>
              <p className="answer-copy">{feedback.answer}</p>
              <h3>Feedback</h3>
              <p className="answer-copy">{feedback.judgment.feedback}</p>
              <h3>Reference answer</h3>
              <p className="answer-copy">{feedback.judgment.modelAnswer}</p>
              <button
                className="primary"
                disabled={Boolean(loading)}
                onClick={() => void next()}
              >
                {loading ||
                  (index + 1 === queue.length
                    ? "See my review"
                    : "Next question →")}
              </button>
            </section>
          ) : current ? (
            <section className="panel practice-question">
              <div className="question-meta">
                <span>{areaName(current.question.area)}</span>
                <span>{current.question.topic}</span>
                <span>{current.question.difficulty.toLowerCase()}</span>
              </div>
              <h2>{current.question.text}</h2>
              <label>
                Your answer
                <textarea
                  rows={8}
                  maxLength={12000}
                  value={answer}
                  disabled={Boolean(loading)}
                  onChange={(e) => setAnswer(e.target.value)}
                  placeholder="Explain what you know, in your own words."
                />
              </label>
              <p className="helper-text">
                Not sure? You can say so—the reference answer will help you
                learn.
              </p>
              <button
                className="primary"
                disabled={!answer.trim() || Boolean(loading)}
                onClick={() => void submit()}
              >
                {loading || "Review my answer"}
              </button>
            </section>
          ) : (
            <section className="panel">
              <p role="status">
                {loading ||
                  "This question needs to be prepared by the interview agent."}
              </p>
              {!loading && (
                <button
                  className="secondary"
                  onClick={async () => {
                    if (busy.current) return;
                    busy.current = true;
                    setError("");
                    try {
                      await prepare(
                        queue,
                        index,
                        queue.flatMap((s) => (s.question ? [s.question] : [])),
                      );
                    } catch (e) {
                      setError(
                        e instanceof Error
                          ? e.message
                          : "Could not prepare question",
                      );
                    } finally {
                      setLoading("");
                      busy.current = false;
                    }
                  }}
                >
                  Retry question
                </button>
              )}
            </section>
          )}
        </>
      )}
      {error && (
        <p className="error" role="alert">
          {error}
        </p>
      )}
    </section>
  );
}
