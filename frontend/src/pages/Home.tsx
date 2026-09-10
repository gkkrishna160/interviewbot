interface HomeProps {
  onStart: () => void;
}

export function Home({ onStart }: HomeProps) {
  return (
    <section className="home">
      <div>
        <p className="eyebrow">AI technical interview engine</p>
        <h1>InterviewBot</h1>
        <p className="intro">
          Run structured software engineering interviews with application-owned state, LLM-generated questions, and
          relevance-first evaluations.
        </p>
      </div>
      <button className="primary" onClick={onStart}>
        Create interview
      </button>
    </section>
  );
}
