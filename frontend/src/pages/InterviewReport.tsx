import { useEffect, useState } from 'react';
import { interviewApi } from '../api/interviewApi';
import { InterviewReportResponse } from '../types/interview';

interface InterviewReportProps {
  interviewId: number;
  onNew: () => void;
}

export function InterviewReport({ interviewId, onNew }: InterviewReportProps) {
  const [report, setReport] = useState<InterviewReportResponse | null>(null);
  const [error, setError] = useState('');

  useEffect(() => {
    interviewApi
      .getReport(interviewId)
      .then(setReport)
      .catch((err) => setError(err instanceof Error ? err.message : 'Unable to load report'));
  }, [interviewId]);

  if (error) {
    return <section className="panel error">{error}</section>;
  }

  if (!report) {
    return <section className="panel">Loading report...</section>;
  }

  return (
    <section className="report">
      <div className="section-header">
        <div>
          <p className="eyebrow">Report</p>
          <h2>Final feedback</h2>
        </div>
        <button className="secondary" onClick={onNew}>
          New interview
        </button>
      </div>

      <div className="report-summary">
        <div>
          <strong>{report.overallScore}/10</strong>
          <span>Overall score</span>
        </div>
        <div>
          <strong>{report.questions.length}</strong>
          <span>Questions asked</span>
        </div>
      </div>

      <div className="columns">
        <List title="Strengths" items={report.strengths} />
        <List title="Weaknesses" items={report.weaknesses} />
        <List title="Recommendations" items={report.recommendations} />
      </div>

      <section className="panel">
        <h3>Topic performance</h3>
        {Object.entries(report.topicWisePerformance).map(([topic, score]) => (
          <div className="topic-row" key={topic}>
            <span>{topic}</span>
            <strong>{Math.round(score * 10) / 10}/10</strong>
          </div>
        ))}
      </section>

      <section className="panel">
        <h3>Question history</h3>
        {report.questions.map((item) => (
          <article className="history-item" key={item.question.id}>
            <div className="question-meta">
              <span>#{item.question.sequence}</span>
              <span>{item.question.topic}</span>
              <span>{item.question.difficulty}</span>
            </div>
            <h4>{item.question.question}</h4>
            <p>{item.answer ?? 'No answer submitted.'}</p>
            {item.evaluation && <p className="feedback">{item.evaluation.feedback}</p>}
          </article>
        ))}
      </section>
    </section>
  );
}

function List({ title, items }: { title: string; items: string[] }) {
  return (
    <section className="panel">
      <h3>{title}</h3>
      {items.length === 0 ? <p>Nothing notable yet.</p> : items.map((item) => <p key={item}>{item}</p>)}
    </section>
  );
}
