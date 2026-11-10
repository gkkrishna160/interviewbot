import { setGenerationAdminKey } from "../api/http";
import { FormEvent, useCallback, useEffect, useRef, useState } from "react";
import {
  bankApi,
  BankDifficulty,
  BankPage,
  BankStatus,
  BankType,
  Category,
  Dashboard,
  Detail,
  Execution,
} from "../api/bankApi";

const label = (value: string) =>
  value
    .toLowerCase()
    .replace(/_/g, " ")
    .replace(/^./, (c) => c.toUpperCase());
const date = (value?: string) =>
  value ? new Date(value).toLocaleString() : "No successful run yet";
const difficulties: BankDifficulty[] = ["BEGINNER", "INTERMEDIATE", "ADVANCED"];
const types: BankType[] = [
  "FUNDAMENTAL",
  "PRACTICAL",
  "DEBUGGING",
  "SYSTEM_DESIGN",
  "ARCHITECTURE",
  "SCENARIO",
];
const statuses: BankStatus[] = [
  "APPROVED",
  "PENDING_REVIEW",
  "REJECTED",
  "DUPLICATE",
];

export function QuestionBank({ onPractice }: { onPractice?: () => void }) {
  const [categories, setCategories] = useState<Category[]>([]);
  const [dashboard, setDashboard] = useState<Dashboard | null>(null);
  const [activity, setActivity] = useState<Execution[]>([]);
  const [category, setCategory] = useState("");
  const [topic, setTopic] = useState("");
  const [difficulty, setDifficulty] = useState("");
  const [type, setType] = useState("");
  const [status, setStatus] = useState<BankStatus>("APPROVED");
  const [search, setSearch] = useState("");
  const [query, setQuery] = useState("");
  const [tag, setTag] = useState("");
  const [page, setPage] = useState(0);
  const [questions, setQuestions] = useState<BankPage | null>(null);
  const [tab, setTab] = useState<"banks" | "questions" | "activity">("banks");
  const [selected, setSelected] = useState<number | null>(null);
  const [error, setError] = useState("");
  const [listError, setListError] = useState("");
  const [loading, setLoading] = useState(true);
  const [running, setRunning] = useState(false);
  const [notice, setNotice] = useState("");
  const [refresh, setRefresh] = useState(0);
  const refreshOverview = useCallback(async () => {
    try {
      const [taxonomy, stats, runs] = await Promise.all([
        bankApi.categories(),
        bankApi.dashboard(),
        bankApi.activity(),
      ]);
      setCategories(taxonomy);
      setDashboard(stats);
      setActivity(runs);
      setError("");
    } catch (err) {
      setError(
        err instanceof Error ? err.message : "Unable to load the question bank",
      );
    }
  }, []);
  useEffect(() => {
    void refreshOverview();
    const timer = window.setInterval(() => {
      void refreshOverview();
      setRefresh((n) => n + 1);
    }, 10000);
    return () => window.clearInterval(timer);
  }, [refreshOverview]);
  useEffect(() => {
    const timer = window.setTimeout(() => {
      setQuery(search);
      setPage(0);
    }, 300);
    return () => window.clearTimeout(timer);
  }, [search]);
  useEffect(() => {
    if (tab !== "questions") return;
    let current = true;
    setLoading(true);
    setListError("");
    const params = new URLSearchParams({
      status,
      page: String(page),
      size: "12",
    });
    Object.entries({
      categoryId: category,
      topicId: topic,
      difficulty,
      questionType: type,
      search: query,
      tag,
    }).forEach(([key, value]) => {
      if (value) params.set(key, value);
    });
    bankApi
      .questions(params)
      .then((result) => {
        if (current) setQuestions(result);
      })
      .catch((err) => {
        if (current)
          setListError(
            err instanceof Error ? err.message : "Unable to load questions",
          );
      })
      .finally(() => {
        if (current) setLoading(false);
      });
    return () => {
      current = false;
    };
  }, [
    category,
    topic,
    difficulty,
    type,
    status,
    query,
    tag,
    page,
    refresh,
    tab,
  ]);
  async function runAgents() {
    setRunning(true);
    setNotice("");
    try {
      const run = await bankApi.run();
      setNotice(
        `Agent cycle #${run.executionId} started. Activity updates automatically.`,
      );
      await refreshOverview();
    } catch (err) {
      setNotice(err instanceof Error ? err.message : "Unable to start agents");
    } finally {
      setRunning(false);
    }
  }
  const activeCategory = categories.find((c) => String(c.id) === category);
  const visibleTopics =
    activeCategory?.topics ?? categories.flatMap((c) => c.topics);
  const gaps = dashboard?.coverage.filter((t) => t.deficit > 0) ?? [];
  function chooseCategory(id: string) {
    setCategory(id);
    setTopic("");
    setPage(0);
    setQuestions(null);
    setTag("");
    setSearch("");
    setQuery("");
    setRefresh((n) => n + 1);
  }
  function reset() {
    chooseCategory(category);
    setDifficulty("");
    setType("");
    setStatus("APPROVED");
    setSearch("");
    setTag("");
  }
  return (
    <section className="bank-layout">
      <aside className="bank-nav">
        <a
          className="bank-brand"
          href="#"
          onClick={(e) => {
            e.preventDefault();
            reset();
            setTab("banks");
          }}
        >
          <span className="brand-mark">ib</span>
          <span>
            InterviewBot<small>Prepare with purpose</small>
          </span>
        </a>
        <p className="eyebrow nav-caption">Your library</p>
        <button
          className={`bank-nav-item ${tab === "questions" && !category ? "active" : ""}`}
          onClick={() => {
            chooseCategory("");
            setTab("questions");
          }}
        >
          <span>All questions</span>
          <span>{dashboard?.counts.APPROVED ?? "—"}</span>
        </button>
        {categories.map((c) => (
          <button
            key={c.id}
            className={`bank-nav-item ${category === String(c.id) && tab === "questions" ? "active" : ""}`}
            onClick={() => {
              chooseCategory(String(c.id));
              setTab("questions");
            }}
          >
            <span>{c.name}</span>
            <span>{c.topics.reduce((sum, t) => sum + t.approved, 0)}</span>
          </button>
        ))}
        <div className="nav-divider" />
        <button
          className={`bank-nav-item ${tab === "activity" ? "active" : ""}`}
          onClick={() => setTab("activity")}
        >
          <span>Agent activity</span>
          {activity.some((a) => a.status === "RUNNING") && (
            <span className="live-dot" />
          )}
        </button>
        {onPractice && (
          <button className="bank-nav-item" onClick={onPractice}>
            <span>Interview practice ↗</span>
          </button>
        )}
        <div className="bank-nav-footer">
          <span className="live-dot" />
          <div>
            <strong>
              {dashboard?.schedulingEnabled
                ? "Background agents enabled"
                : "Manual agent runs"}
            </strong>
            <small>
              {dashboard
                ? `${dashboard.provider} · ${dashboard.model}`
                : "Connecting to your library…"}
            </small>
          </div>
        </div>
      </aside>
      <div className="bank-main">
        <header className="bank-header">
          <div>
            <p className="eyebrow">Learn. Understand. Explain.</p>
            <h1>
              {tab === "activity"
                ? "Behind the question bank"
                : "Your next great answer starts here."}
            </h1>
            <p>
              {tab === "activity"
                ? "Follow the agents that build and review your library."
                : "Explore technical concepts, real engineering scenarios, and system design."}
            </p>
          </div>
          {onPractice && (
            <button className="primary" onClick={onPractice}>
              Start practicing ↗
            </button>
          )}
        </header>
        {error && (
          <p className="error" role="alert">
            {error}{" "}
            <button
              className="secondary"
              onClick={() => void refreshOverview()}
            >
              Retry
            </button>
          </p>
        )}
        <div className="bank-metrics">
          <Metric
            name="Approved questions"
            value={dashboard?.counts.APPROVED}
            accent
          />
          <Metric name="AI generated" value={dashboard?.generated} />
          <Metric
            name="Awaiting review"
            value={dashboard?.counts.PENDING_REVIEW}
          />
          <Metric name="Rejected" value={dashboard?.counts.REJECTED} />
          <Metric name="Duplicates found" value={dashboard?.counts.DUPLICATE} />
        </div>
        <div className="bank-section-title">
          <div className="bank-tabs">
            <button
              className={tab === "banks" ? "selected" : ""}
              onClick={() => setTab("banks")}
            >
              Topic banks
            </button>
            <button
              className={tab === "questions" ? "selected" : ""}
              onClick={() => setTab("questions")}
            >
              Question bank
            </button>
            <button
              className={tab === "activity" ? "selected" : ""}
              onClick={() => setTab("activity")}
            >
              Agent activity
            </button>
          </div>
          <span className="bank-muted">Updates every 10 seconds</span>
        </div>
        {tab === "banks" ? (
          <section className="bank-domain-grid" aria-label="Topic banks">
            {categories.map((bank) => (
              <article className="bank-domain-card" key={bank.id}>
                <p className="eyebrow">{bank.topics.length} topics</p>
                <h2>{bank.name}</h2>
                <p>
                  {bank.topics
                    .slice(0, 5)
                    .map((t) => t.name)
                    .join(" · ")}
                  {bank.topics.length > 5 ? " …" : ""}
                </p>
                <div className="bank-domain-counts">
                  <span>
                    <strong>
                      {bank.topics.reduce((n, t) => n + t.approved, 0)}
                    </strong>{" "}
                    approved
                  </span>
                  <span>
                    {bank.topics.reduce((n, t) => n + t.pending, 0)} awaiting
                    review
                  </span>
                </div>
                <button
                  className="secondary"
                  onClick={() => {
                    chooseCategory(String(bank.id));
                    setTab("questions");
                  }}
                >
                  Browse {bank.name} →
                </button>
              </article>
            ))}
            {!categories.length && !error && (
              <p role="status">Loading your topic banks…</p>
            )}
          </section>
        ) : tab === "questions" ? (
          <>
            <div className="bank-filter-panel">
              <label className="bank-search">
                <span className="sr-only">Search questions</span>
                <input
                  type="search"
                  maxLength={200}
                  placeholder="Search a concept or question…"
                  value={search}
                  onChange={(e) => setSearch(e.target.value)}
                />
              </label>
              <div className="bank-filters">
                <label>
                  Topic
                  <select
                    value={topic}
                    onChange={(e) => {
                      setTopic(e.target.value);
                      setPage(0);
                    }}
                  >
                    <option value="">All topics</option>
                    {visibleTopics.map((t) => (
                      <option key={t.id} value={t.id}>
                        {t.name}
                      </option>
                    ))}
                  </select>
                </label>
                <label>
                  Difficulty
                  <select
                    value={difficulty}
                    onChange={(e) => {
                      setDifficulty(e.target.value);
                      setPage(0);
                    }}
                  >
                    <option value="">All levels</option>
                    {difficulties.map((d) => (
                      <option key={d} value={d}>
                        {label(d)}
                      </option>
                    ))}
                  </select>
                </label>
                <label>
                  Question type
                  <select
                    value={type}
                    onChange={(e) => {
                      setType(e.target.value);
                      setPage(0);
                    }}
                  >
                    <option value="">All types</option>
                    {types.map((t) => (
                      <option key={t} value={t}>
                        {label(t)}
                      </option>
                    ))}
                  </select>
                </label>
                <label>
                  Review status
                  <select
                    value={status}
                    onChange={(e) => {
                      setStatus(e.target.value as BankStatus);
                      setPage(0);
                    }}
                  >
                    {statuses.map((s) => (
                      <option key={s} value={s}>
                        {label(s)}
                      </option>
                    ))}
                  </select>
                </label>
              </div>
            </div>
            <div className="bank-results-heading">
              <h2>
                {activeCategory?.name ?? "All categories"}{" "}
                <span>{questions?.totalElements ?? "—"} questions</span>
              </h2>
              <button className="text-button" onClick={reset}>
                Reset filters
              </button>
            </div>
            {tag && (
              <button
                className="bank-badge"
                onClick={() => {
                  setTag("");
                  setPage(0);
                }}
              >
                Tag: {tag} ×
              </button>
            )}
            {listError ? (
              <p className="error" role="alert">
                {listError}{" "}
                <button
                  className="secondary"
                  onClick={() => setRefresh((n) => n + 1)}
                >
                  Retry
                </button>
              </p>
            ) : loading && !questions ? (
              <div className="bank-empty" role="status">
                Loading your question bank…
              </div>
            ) : questions?.content.length === 0 ? (
              <div className="bank-empty">
                <span className="empty-mark">?</span>
                <h3>
                  {status === "APPROVED"
                    ? "Room for your next discovery"
                    : "No questions in this review status"}
                </h3>
                <p>
                  {dashboard?.generated === 0
                    ? "Run the agents to generate and independently review your first batch."
                    : "Try another topic, difficulty, or search term."}
                </p>
                <button className="secondary" onClick={reset}>
                  Clear filters
                </button>
              </div>
            ) : (
              <div
                className={`bank-question-list ${loading ? "refreshing" : ""}`}
                aria-busy={loading}
              >
                {questions?.content.map((q) => (
                  <article className="bank-question-card" key={q.id}>
                    <div className="bank-question-top">
                      <span className="bank-muted">
                        {q.category} / {q.topic}
                        {q.subtopic ? ` / ${q.subtopic}` : ""}
                      </span>
                      <span
                        className={`bank-level level-${q.difficulty.toLowerCase()}`}
                      >
                        {label(q.difficulty)}
                      </span>
                    </div>
                    <button
                      className="question-title"
                      onClick={() => setSelected(q.id)}
                    >
                      {q.text}
                    </button>
                    <div className="bank-question-bottom">
                      <div className="bank-tags">
                        <span className="bank-type">
                          {label(q.questionType)}
                        </span>
                        {q.tags.slice(0, 3).map((t) => (
                          <button
                            key={t}
                            className="bank-tag"
                            onClick={() => {
                              setTag(t);
                              setPage(0);
                            }}
                          >
                            {t}
                          </button>
                        ))}
                      </div>
                      <span className="bank-muted">
                        {q.status === "APPROVED"
                          ? `Relevance ${q.relevanceScore ?? "—"}/100`
                          : label(q.status)}{" "}
                        <button
                          className="text-button"
                          aria-label={`View question ${q.id}`}
                          onClick={() => setSelected(q.id)}
                        >
                          View ↗
                        </button>
                      </span>
                    </div>
                  </article>
                ))}
              </div>
            )}
            {questions && questions.totalPages > 0 && (
              <footer className="bank-pagination">
                <span>
                  Page {page + 1} of {questions.totalPages}
                </span>
                <div>
                  <button
                    className="secondary"
                    disabled={page === 0 || loading}
                    onClick={() => setPage((n) => n - 1)}
                  >
                    ← Previous
                  </button>
                  <button
                    className="secondary"
                    disabled={page + 1 >= questions.totalPages || loading}
                    onClick={() => setPage((n) => n + 1)}
                  >
                    Next →
                  </button>
                </div>
              </footer>
            )}
          </>
        ) : (
          <section className="bank-activity-section">
            <label className="bank-admin-key">
              Administrator key (if configured)
              <input
                type="password"
                autoComplete="off"
                onChange={(e) => setGenerationAdminKey(e.target.value)}
                placeholder="For protected generation controls"
              />
            </label>
            <div className="bank-agent-chain">
              <div>
                <span>01</span>
                <strong>Orchestrator</strong>
                <p>Find gaps and delegate</p>
              </div>
              <i aria-hidden="true">→</i>
              <div>
                <span>02</span>
                <strong>Generation agent</strong>
                <p>Write focused questions</p>
              </div>
              <i aria-hidden="true">→</i>
              <div>
                <span>03</span>
                <strong>Review agent</strong>
                <p>Check, classify, compare</p>
              </div>
            </div>
            <div className="bank-activity-heading">
              <div>
                <h2>Recent executions</h2>
                <p className="bank-muted">
                  Last successful cycle:{" "}
                  {date(dashboard?.lastSuccessfulExecution)}
                </p>
              </div>
              <button
                className="primary"
                disabled={
                  running ||
                  activity.some(
                    (a) => a.agent === "orchestrator" && a.status === "RUNNING",
                  )
                }
                onClick={() => void runAgents()}
              >
                {running ? "Starting…" : "Run agents"}
              </button>
            </div>
            {notice && (
              <p className="bank-notice" role="status">
                {notice}
              </p>
            )}
            {dashboard && dashboard.reviewFailures > 0 && (
              <p className="bank-notice">
                {dashboard.reviewFailures} pending questions exhausted their
                review retries. Open a question to request re-review after
                resolving the provider issue.
              </p>
            )}
            {activity.length === 0 ? (
              <div className="bank-empty">
                <h3>The agents are ready when you are.</h3>
                <p>
                  Start a cycle to build coverage. Generated questions pass
                  through independent review before appearing in the approved
                  bank.
                </p>
              </div>
            ) : (
              <div className="bank-executions">
                {activity.map((run) => (
                  <article key={run.id}>
                    <span
                      className={`execution-state state-${run.status.toLowerCase()}`}
                    >
                      {label(run.status)}
                    </span>
                    <div>
                      <strong>
                        {label(run.agent)}{" "}
                        <small>
                          #{run.id} · {run.model} · attempt {run.attempt}
                        </small>
                      </strong>
                      <p>{run.error ?? run.summary ?? "Working…"}</p>
                      {run.questionId && (
                        <button
                          className="text-button"
                          onClick={() => setSelected(run.questionId!)}
                        >
                          Question #{run.questionId}
                        </button>
                      )}
                    </div>
                    <time dateTime={run.createdAt}>{date(run.createdAt)}</time>
                  </article>
                ))}
              </div>
            )}
          </section>
        )}
      </div>
      <aside className="bank-coverage">
        <div className="coverage-heading">
          <span className="eyebrow">Always growing</span>
          <h2>A well-rounded library</h2>
          <p>Coverage improves one reviewed batch at a time.</p>
        </div>
        <div className="coverage-summary">
          <strong>{gaps.length}</strong>
          <span>topics need more coverage</span>
        </div>
        {dashboard?.coverage
          .slice()
          .sort((a, b) => b.deficit - a.deficit)
          .slice(0, 8)
          .map((t) => (
            <button
              key={t.id}
              className="coverage-topic"
              onClick={() => {
                chooseCategory(String(t.categoryId));
                setTopic(String(t.id));
                setTab("questions");
              }}
            >
              <span>{t.name}</span>
              <small>
                {t.approved} approved · {t.deficit} needed
              </small>
              <span className="coverage-track">
                <span
                  style={{
                    width: `${Math.max(0, Math.min(100, (t.approved / (t.approved + t.deficit || 1)) * 100))}%`,
                  }}
                />
              </span>
            </button>
          ))}
        <div className="coverage-callout">
          <strong>Quality before quantity.</strong>
          <p>
            Every new question is checked for relevance, clarity, technical
            accuracy, and duplication.
          </p>
          <button className="secondary" onClick={() => setTab("activity")}>
            See agent activity →
          </button>
        </div>
      </aside>
      {selected !== null && (
        <QuestionDetail
          id={selected}
          onClose={() => setSelected(null)}
          onChanged={() => {
            setRefresh((n) => n + 1);
            void refreshOverview();
          }}
        />
      )}
    </section>
  );
}
function Metric({
  name,
  value,
  accent,
}: {
  name: string;
  value?: number;
  accent?: boolean;
}) {
  return (
    <div className={`bank-metric ${accent ? "accent" : ""}`}>
      <span>{name}</span>
      <strong>{value === undefined ? "—" : value.toLocaleString()}</strong>
    </div>
  );
}

function QuestionDetail({
  id,
  onClose,
  onChanged,
}: {
  id: number;
  onClose: () => void;
  onChanged: () => void;
}) {
  const dialog = useRef<HTMLDialogElement>(null);
  const [detail, setDetail] = useState<Detail | null>(null);
  const [error, setError] = useState("");
  const [reason, setReason] = useState("");
  const [busy, setBusy] = useState(false);
  useEffect(() => {
    dialog.current?.showModal();
  }, []);
  useEffect(() => {
    let current = true;
    bankApi
      .detail(id)
      .then((d) => {
        if (current) setDetail(d);
      })
      .catch((err) => {
        if (current) setError(err.message);
      });
    return () => {
      current = false;
    };
  }, [id]);
  async function requeue(event: FormEvent) {
    event.preventDefault();
    setBusy(true);
    setError("");
    try {
      setDetail(await bankApi.requeue(id, reason));
      setReason("");
      onChanged();
    } catch (err) {
      setError(err instanceof Error ? err.message : "Unable to request review");
    } finally {
      setBusy(false);
    }
  }
  const q = detail?.question;
  return (
    <dialog
      className="bank-detail"
      ref={dialog}
      onCancel={onClose}
      aria-labelledby="detail-title"
    >
      <button
        className="detail-close secondary"
        onClick={onClose}
        aria-label="Close question details"
      >
        ×
      </button>
      <p className="eyebrow">Question #{id}</p>
      <h2 id="detail-title">Question details</h2>
      {error && (
        <p className="error" role="alert">
          {error}
        </p>
      )}
      {!q ? (
        <p>Loading question…</p>
      ) : (
        <>
          <p className="bank-muted">
            {q.category} / {q.topic}
            {q.subtopic && ` / ${q.subtopic}`}
          </p>
          <h3 className="detail-question">{q.text}</h3>
          <div className="bank-tags">
            <span className="bank-type">{label(q.questionType)}</span>
            <span className={`bank-level level-${q.difficulty.toLowerCase()}`}>
              {label(q.difficulty)}
            </span>
            <span className="bank-badge">{label(q.status)}</span>
            {q.tags.map((t) => (
              <span className="bank-tag" key={t}>
                {t}
              </span>
            ))}
          </div>
          <dl className="detail-metadata">
            <div>
              <dt>Quality</dt>
              <dd>
                {q.qualityScore ?? "Awaiting review"}
                {q.qualityScore != null && "/100"}
              </dd>
            </div>
            <div>
              <dt>Interview relevance</dt>
              <dd>
                {q.relevanceScore ?? "Awaiting review"}
                {q.relevanceScore != null && "/100"}
              </dd>
            </div>
            <div>
              <dt>Source</dt>
              <dd>{label(q.source)}</dd>
            </div>
            <div>
              <dt>Created</dt>
              <dd>{date(q.createdAt)}</dd>
            </div>
          </dl>
          {q.duplicateOfId && <p>Duplicate of question #{q.duplicateOfId}.</p>}
          <h3>Review history</h3>
          {detail!.validations.length === 0 ? (
            <p className="bank-muted">
              This question is waiting for its first independent review.
            </p>
          ) : (
            detail!.validations.map((v) => (
              <article className="validation-item" key={v.id}>
                <strong>
                  {label(v.previousStatus)} → {label(v.decision)}
                </strong>
                <p>{v.reason}</p>
                {v.classification && (
                  <p className="bank-muted">{v.classification}</p>
                )}
                <small>
                  {v.reviewer} · {v.model} · {date(v.createdAt)}
                </small>
              </article>
            ))
          )}
          {(q.status !== "PENDING_REVIEW" || q.reviewAttempts > 0) && (
            <form className="requeue-form" onSubmit={requeue}>
              <label>
                Reason for another review
                <input
                  required
                  maxLength={1000}
                  value={reason}
                  onChange={(e) => setReason(e.target.value)}
                />
              </label>
              <button className="secondary" disabled={busy || !reason.trim()}>
                {busy ? "Requesting…" : "Request re-review"}
              </button>
            </form>
          )}
        </>
      )}
    </dialog>
  );
}
