import { useState } from 'react';
import { interviewApi } from '../api/interviewApi';
import { EvaluationResponse, InterviewResponse, QuestionResponse } from '../types/interview';

interface InterviewScreenProps {
  interview: InterviewResponse;
  initialQuestion: QuestionResponse;
  onReport: () => void;
  onInterviewUpdated: (interview: InterviewResponse) => void;
}

export function InterviewScreen({ interview, initialQuestion, onReport, onInterviewUpdated }: InterviewScreenProps) {
  const [question, setQuestion] = useState(initialQuestion);
  const [answer, setAnswer] = useState('');
  const [evaluation, setEvaluation] = useState<EvaluationResponse | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState('');

  async function submitAnswer() {
    setLoading(true);
    setError('');
    try {
      const response = await interviewApi.submitAnswer(interview.id, answer);
      setEvaluation(response.evaluation);
      setAnswer('');
      const refreshed = await interviewApi.getInterview(interview.id);
      onInterviewUpdated(refreshed);
      if (response.nextQuestion) {
        setQuestion(response.nextQuestion);
      }
      if (response.status === 'COMPLETED') {
        onReport();
      }
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Unable to submit answer');
    } finally {
      setLoading(false);
    }
  }

  async function complete() {
    setLoading(true);
    try {
      const refreshed = await interviewApi.completeInterview(interview.id);
      onInterviewUpdated(refreshed);
      onReport();
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Unable to complete interview');
    } finally {
      setLoading(false);
    }
  }

  return (
    <section className="interview-workspace">
      <header className="interview-topbar">
        <div>
          <p className="eyebrow">Live interview</p>
          <h2>{interview.role}</h2>
        </div>
        <div className="topbar-actions">
          <span className="status-pill">{interview.status.replace(/_/g, ' ')}</span>
          <button className="secondary" onClick={complete} disabled={loading}>
            Complete interview
          </button>
        </div>
      </header>

      <div className="interview-stats">
        <Metric label="Company" value={interview.company} />
        <Metric label="Duration" value={`${interview.durationMinutes} min`} />
        <Metric label="Question" value={`#${question.sequence}`} />
        <Metric label="Mode" value={question.questionType === 'FOLLOW_UP' ? 'Follow-up' : 'Primary'} />
      </div>

      <section className="question-panel">
        <div className="question-header">
          <div>
            <h1 className="question-title">{question.question}</h1>
          </div>
          <div className="question-side">
            <div className="question-meta">
              <span>{question.topic}</span>
              <span>{question.difficulty}</span>
            </div>
            <div className="question-number">Q{question.sequence}</div>
          </div>
        </div>
        <div className="concepts">
          {question.expectedConcepts.map((concept) => (
            <span key={concept}>{concept}</span>
          ))}
        </div>
      </section>

      <section className="answer-layout">
        <div className="answer-panel">
          <div className="panel-heading">
            <h3>Your answer</h3>
            <span>{answer.length} chars</span>
          </div>
          <textarea
            className="answer-box"
            value={answer}
            onChange={(event) => setAnswer(event.target.value)}
            placeholder="Type your answer..."
          />
          {error && <div className="error">{error}</div>}
          <div className="answer-actions">
            <button className="primary" onClick={submitAnswer} disabled={loading || !answer.trim()}>
              {loading ? 'Evaluating...' : 'Submit answer'}
            </button>
          </div>
        </div>

        {evaluation && (
          <aside className="evaluation-panel">
            <div className="panel-heading">
              <h3>Latest evaluation</h3>
              <span>{evaluation.answeredCorrectly ? 'Accepted' : 'Needs work'}</span>
            </div>
            <div className="score-grid">
              <Score label="Relevance" value={evaluation.relevanceScore} />
              <Score label="Technical" value={evaluation.technicalScore} />
              <Score label="Correctness" value={evaluation.correctnessScore} />
            </div>
            <p>{evaluation.feedback}</p>
            {evaluation.needsRetry && evaluation.followUpQuestion && (
              <p className="retry">A new follow-up question will be asked next.</p>
            )}
          </aside>
        )}
      </section>
    </section>
  );
}

function Metric({ label, value }: { label: string; value: string }) {
  return (
    <div>
      <span>{label}</span>
      <strong>{value}</strong>
    </div>
  );
}

function Score({ label, value }: { label: string; value: number }) {
  return (
    <div>
      <strong>{value}/10</strong>
      <span>{label}</span>
    </div>
  );
}
