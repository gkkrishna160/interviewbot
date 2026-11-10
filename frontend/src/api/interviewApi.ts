import { request } from "./http";
import type {
  AppConfig,
  Area,
  Judgment,
  PracticeQuestion,
  QuestionSelection,
} from "../types/interview";
export const interviewApi = {
  config: () => request<AppConfig>("/config", {}, 15000),
  questions: (areas: Area[]) =>
    request<QuestionSelection>(
      "/interview/questions",
      { method: "POST", body: JSON.stringify({ areas }) },
      15000,
    ),
  next: (area: Area, previousQuestions: string[]) =>
    request<PracticeQuestion>("/interview/next-question", {
      method: "POST",
      body: JSON.stringify({ area, previousQuestions }),
    }),
  assess: (questionToken: string, answer: string) =>
    request<Judgment>("/interview/assess", {
      method: "POST",
      body: JSON.stringify({ questionToken, answer }),
    }),
};
