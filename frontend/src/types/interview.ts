export type Area = "BACKEND" | "FRONTEND" | "AI";
export interface AppConfig {
  mode: "interview_mode" | "generation_mode";
  questionsPerArea: number;
  areas: { id: Area; name: string; approved: number; questions: number }[];
}
export interface QuestionData {
  id: string;
  area: Area;
  text: string;
  topic: string;
  subtopic?: string;
  difficulty: string;
  questionType: string;
  source: "BANK" | "INTERVIEW_AGENT";
}
export interface PracticeQuestion {
  question: QuestionData;
  token: string;
}
export interface QuestionSelection {
  questions: PracticeQuestion[];
  shortfalls: { area: Area; count: number }[];
  total: number;
}
export interface Judgment {
  score: number;
  feedback: string;
  modelAnswer: string;
}
export interface PracticeResult {
  question: PracticeQuestion;
  answer: string;
  judgment: Judgment;
}
