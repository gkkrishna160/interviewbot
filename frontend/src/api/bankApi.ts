import { request } from "./http";
export type BankStatus =
  | "PENDING_REVIEW"
  | "APPROVED"
  | "REJECTED"
  | "DUPLICATE";
export type BankDifficulty = "BEGINNER" | "INTERMEDIATE" | "ADVANCED";
export type BankType =
  | "FUNDAMENTAL"
  | "PRACTICAL"
  | "DEBUGGING"
  | "SYSTEM_DESIGN"
  | "ARCHITECTURE"
  | "SCENARIO";
export interface Topic {
  id: number;
  categoryId: number;
  category: string;
  name: string;
  approved: number;
  pending: number;
  deficit: number;
}
export interface Category {
  id: number;
  name: string;
  topics: Topic[];
}
export interface BankQuestion {
  id: number;
  text: string;
  categoryId: number;
  category: string;
  topicId: number;
  topic: string;
  subtopic?: string;
  difficulty: BankDifficulty;
  questionType: BankType;
  tags: string[];
  status: BankStatus;
  source: string;
  qualityScore?: number;
  relevanceScore?: number;
  duplicateOfId?: number;
  reviewAttempts: number;
  createdAt: string;
  updatedAt: string;
}
export interface BankPage {
  content: BankQuestion[];
  totalElements: number;
  totalPages: number;
  page: number;
}
export interface Validation {
  id: number;
  previousStatus: BankStatus;
  decision: BankStatus;
  reason: string;
  reviewer: string;
  model: string;
  classification?: string;
  createdAt: string;
}
export interface Detail {
  question: BankQuestion;
  validations: Validation[];
}
export interface Dashboard {
  counts: Record<BankStatus, number>;
  generated: number;
  reviewFailures: number;
  coverage: Topic[];
  lastSuccessfulExecution?: string;
  schedulingEnabled: boolean;
  provider: string;
  model: string;
}
export interface Execution {
  id: number;
  agent: string;
  model: string;
  status: "RUNNING" | "SUCCEEDED" | "FAILED" | "TIMED_OUT";
  attempt: number;
  batchId?: number;
  questionId?: number;
  createdAt: string;
  finishedAt?: string;
  error?: string;
  summary?: string;
}
const base = "/question-bank";
export const bankApi = {
  categories: () => request<Category[]>(`${base}/categories`),
  dashboard: () => request<Dashboard>(`${base}/dashboard`),
  activity: () => request<Execution[]>(`${base}/agents/executions`),
  questions: (params: URLSearchParams) =>
    request<BankPage>(`${base}/questions?${params}`),
  detail: (id: number) => request<Detail>(`${base}/questions/${id}`),
  run: () =>
    request<{ accepted: boolean; executionId: number }>(`${base}/agents/run`, {
      method: "POST",
    }),
  requeue: (id: number, reason: string) =>
    request<Detail>(`${base}/questions/${id}/requeue`, {
      method: "POST",
      body: JSON.stringify({ reason }),
    }),
};
