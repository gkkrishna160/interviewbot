import {
  CreateInterviewRequest,
  InterviewReportResponse,
  InterviewResponse,
  QuestionResponse,
  SubmitAnswerResponse,
} from '../types/interview';

const API_BASE_URL = import.meta.env.VITE_API_BASE_URL ?? 'http://localhost:8080/api';

async function request<T>(path: string, options: RequestInit = {}, timeoutMs = 120000): Promise<T> {
  const controller = new AbortController();
  const timeoutId = window.setTimeout(() => controller.abort(), timeoutMs);

  try {
    const response = await fetch(`${API_BASE_URL}${path}`, {
      headers: {
        'Content-Type': 'application/json',
        ...options.headers,
      },
      ...options,
      signal: controller.signal,
    });

    if (!response.ok) {
      const error = await response.json().catch(() => ({ message: response.statusText }));
      throw new Error(error.message ?? 'Request failed');
    }

    return response.json() as Promise<T>;
  } catch (error) {
    if (error instanceof DOMException && error.name === 'AbortError') {
      throw new Error('The request timed out. Check that the backend, MySQL, and Ollama are running.');
    }
    throw error;
  } finally {
    window.clearTimeout(timeoutId);
  }
}

export const interviewApi = {
  createInterview(payload: CreateInterviewRequest) {
    return request<InterviewResponse>('/interviews', {
      method: 'POST',
      body: JSON.stringify(payload),
    });
  },
  startInterview(id: number) {
    return request<QuestionResponse>(`/interviews/${id}/start`, { method: 'POST' }, 180000);
  },
  getInterview(id: number) {
    return request<InterviewResponse>(`/interviews/${id}`);
  },
  getCurrentQuestion(id: number) {
    return request<QuestionResponse>(`/interviews/${id}/current-question`);
  },
  submitAnswer(id: number, answer: string) {
    return request<SubmitAnswerResponse>(`/interviews/${id}/answers`, {
      method: 'POST',
      body: JSON.stringify({ answer }),
    }, 180000);
  },
  completeInterview(id: number) {
    return request<InterviewResponse>(`/interviews/${id}/complete`, { method: 'POST' });
  },
  getReport(id: number) {
    return request<InterviewReportResponse>(`/interviews/${id}/report`);
  },
};
