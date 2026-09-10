import { FormEvent, useState } from 'react';
import { interviewApi } from '../api/interviewApi';
import { CreateInterviewRequest, InterviewResponse, QuestionResponse } from '../types/interview';

interface InterviewSetupForm {
  company: string;
  role: string;
  experienceLevel: string;
  languages: string;
  frameworks: string;
  topics: string;
  durationMinutes: number;
}

const defaults: InterviewSetupForm = {
  company: 'Virtusa',
  role: 'Backend Developer',
  experienceLevel: '5+ years',
  languages: 'Java',
  frameworks: 'Spring Boot, Spring Cloud',
  topics: 'Core Java, Concurrency, Spring Boot, Microservices, SQL, DSA, System Design',
  durationMinutes: 45,
};

interface InterviewSetupProps {
  onInterviewStarted: (interview: InterviewResponse, question: QuestionResponse) => void;
}

export function InterviewSetup({ onInterviewStarted }: InterviewSetupProps) {
  const [form, setForm] = useState(defaults);
  const [loading, setLoading] = useState(false);
  const [loadingMessage, setLoadingMessage] = useState('');
  const [error, setError] = useState('');

  async function submit(event: FormEvent) {
    event.preventDefault();
    setLoading(true);
    setLoadingMessage('Creating interview...');
    setError('');
    try {
      const payload: CreateInterviewRequest = {
        ...form,
        languages: parseCsv(form.languages),
        frameworks: parseCsv(form.frameworks),
        topics: parseCsv(form.topics),
      };
      const created = await interviewApi.createInterview(payload);
      setLoadingMessage('Generating the first question with Ollama...');
      const firstQuestion = await interviewApi.startInterview(created.id);
      setLoadingMessage('Opening interview...');
      const refreshed = await interviewApi.getInterview(created.id);
      onInterviewStarted(refreshed, firstQuestion);
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Unable to start interview');
    } finally {
      setLoading(false);
      setLoadingMessage('');
    }
  }

  return (
    <section className="panel">
      <div className="section-header">
        <div>
          <p className="eyebrow">Setup</p>
          <h2>Create interview</h2>
        </div>
      </div>
      <form className="setup-grid" onSubmit={submit}>
        <label>
          Company
          <input value={form.company} onChange={(event) => setForm({ ...form, company: event.target.value })} />
        </label>
        <label>
          Role
          <input value={form.role} onChange={(event) => setForm({ ...form, role: event.target.value })} />
        </label>
        <label>
          Experience level
          <input
            value={form.experienceLevel}
            onChange={(event) => setForm({ ...form, experienceLevel: event.target.value })}
          />
        </label>
        <label>
          Duration
          <input
            type="number"
            min={10}
            value={form.durationMinutes}
            onChange={(event) => setForm({ ...form, durationMinutes: Number(event.target.value) })}
          />
        </label>
        <label>
          Languages
          <input value={form.languages} onChange={(event) => setForm({ ...form, languages: event.target.value })} />
        </label>
        <label>
          Frameworks
          <input value={form.frameworks} onChange={(event) => setForm({ ...form, frameworks: event.target.value })} />
        </label>
        <label className="wide">
          Topics
          <textarea value={form.topics} onChange={(event) => setForm({ ...form, topics: event.target.value })} />
        </label>
        {loadingMessage && <div className="loading wide">{loadingMessage}</div>}
        {error && <div className="error wide">{error}</div>}
        <button className="primary wide" disabled={loading}>
          {loading ? 'Starting interview...' : 'Start interview'}
        </button>
      </form>
    </section>
  );
}

function parseCsv(value: string | string[]) {
  const text = Array.isArray(value) ? value.join(', ') : value;
  return text
    .split(',')
    .map((item) => item.trim())
    .filter(Boolean);
}
