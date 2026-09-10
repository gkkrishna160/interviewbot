export type Difficulty = 'EASY' | 'MEDIUM' | 'HARD';
export type QuestionType = 'PRIMARY' | 'FOLLOW_UP';
export type InterviewStatus =
  | 'CREATED'
  | 'READY'
  | 'ASKING'
  | 'WAITING_FOR_ANSWER'
  | 'EVALUATING'
  | 'FOLLOW_UP'
  | 'COMPLETED';

export interface CreateInterviewRequest {
  company: string;
  role: string;
  experienceLevel: string;
  languages: string[];
  frameworks: string[];
  topics: string[];
  durationMinutes: number;
}

export interface InterviewResponse extends CreateInterviewRequest {
  id: number;
  status: InterviewStatus;
  startedAt?: string;
  completedAt?: string;
  createdAt: string;
}

export interface QuestionResponse {
  id: number;
  question: string;
  topic: string;
  difficulty: Difficulty;
  questionType: QuestionType;
  sequence: number;
  expectedConcepts: string[];
}

export interface EvaluationResponse {
  id: number;
  relevanceScore: number;
  technicalScore: number;
  correctnessScore: number;
  feedback: string;
  answeredCorrectly: boolean;
  needsRetry: boolean;
  followUpQuestion?: string;
}

export interface SubmitAnswerResponse {
  evaluation: EvaluationResponse;
  nextQuestion?: QuestionResponse;
  status: InterviewStatus;
}

export interface ReportQuestionItem {
  question: QuestionResponse;
  answer?: string;
  evaluation?: EvaluationResponse;
}

export interface InterviewReportResponse {
  interviewId: number;
  overallScore: number;
  topicWisePerformance: Record<string, number>;
  strengths: string[];
  weaknesses: string[];
  recommendations: string[];
  questions: ReportQuestionItem[];
}
