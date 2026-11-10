import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import {
  cleanup,
  fireEvent,
  render,
  screen,
  waitFor,
} from "@testing-library/react";
import { MockInterview } from "../src/pages/MockInterview";
import { interviewApi } from "../src/api/interviewApi";
import App from "../src/App";
import type { AppConfig, PracticeQuestion } from "../src/types/interview";
const config: AppConfig = {
  mode: "interview_mode",
  questionsPerArea: 10,
  areas: [
    {
      id: "BACKEND",
      name: "Backend / Java / Spring Boot",
      approved: 12,
      questions: 10,
    },
    { id: "FRONTEND", name: "Frontend / React", approved: 4, questions: 10 },
    { id: "AI", name: "AI Engineering", approved: 0, questions: 10 },
  ],
};
const question: PracticeQuestion = {
  question: {
    id: "bank-1",
    area: "BACKEND",
    text: "Why is String immutable in Java, and what benefits does this provide?",
    topic: "Core Java",
    difficulty: "BEGINNER",
    questionType: "FUNDAMENTAL",
    source: "BANK",
  },
  token: "signed-question-1",
};
const fallback: PracticeQuestion = {
  question: {
    ...question.question,
    id: "live-2",
    text: "How can lock ordering help prevent a deadlock in a Java service?",
    source: "INTERVIEW_AGENT",
  },
  token: "signed-question-2",
};
const judgment = {
  score: 8,
  feedback: "Correct foundation. Explain safe sharing as well.",
  modelAnswer:
    "Strings are immutable to enable safe sharing, stable hashing and string pooling.",
};
afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
});
beforeEach(() => {
  vi.spyOn(interviewApi, "questions").mockResolvedValue({
    questions: [question],
    shortfalls: [],
    total: 10,
  });
  vi.spyOn(interviewApi, "assess").mockResolvedValue(judgment);
  vi.spyOn(interviewApi, "next").mockResolvedValue(fallback);
});
async function start() {
  fireEvent.click(screen.getByRole("checkbox", { name: /Backend/ }));
  fireEvent.click(screen.getByRole("button", { name: /Start practice/ }));
  await screen.findByRole("heading", { name: question.question.text });
}
describe("Stateless interview practice", () => {
  it("lets candidates combine areas and selects ten questions per area without an AI generation call", async () => {
    render(<MockInterview config={config} />);
    fireEvent.click(screen.getByRole("checkbox", { name: /Backend/ }));
    fireEvent.click(screen.getByRole("checkbox", { name: /AI Engineering/ }));
    fireEvent.click(
      screen.getByRole("button", { name: "Start practice · 20 questions" }),
    );
    await screen.findByRole("heading", { name: question.question.text });
    expect(interviewApi.questions).toHaveBeenCalledWith(["BACKEND", "AI"]);
    expect(interviewApi.next).not.toHaveBeenCalled();
    expect(interviewApi.assess).not.toHaveBeenCalled();
  });
  it("shows score, feedback and reference answer immediately, then gets a missing question only when needed", async () => {
    vi.mocked(interviewApi.questions).mockResolvedValue({
      questions: [question],
      shortfalls: [{ area: "BACKEND", count: 1 }],
      total: 2,
    });
    render(<MockInterview config={config} />);
    await start();
    fireEvent.change(screen.getByLabelText("Your answer"), {
      target: { value: "Strings cannot be changed after creation." },
    });
    fireEvent.click(screen.getByRole("button", { name: "Review my answer" }));
    await screen.findByText(judgment.feedback);
    expect(screen.getByText(judgment.modelAnswer)).toBeTruthy();
    expect(interviewApi.assess).toHaveBeenCalledWith(
      question.token,
      "Strings cannot be changed after creation.",
    );
    expect(interviewApi.next).not.toHaveBeenCalled();
    fireEvent.click(screen.getByRole("button", { name: "Next question →" }));
    await screen.findByRole("heading", { name: fallback.question.text });
    expect(interviewApi.next).toHaveBeenCalledWith("BACKEND", [question.token]);
    fireEvent.change(screen.getByLabelText("Your answer"), {
      target: { value: "Always acquire locks in the same global order." },
    });
    fireEvent.click(screen.getByRole("button", { name: "Review my answer" }));
    await screen.findByRole("button", { name: "See my review" });
    fireEvent.click(screen.getByRole("button", { name: "See my review" }));
    await screen.findByText("Your practice review");
    expect(screen.getByText(/2 questions answered/)).toBeTruthy();
  });
  it("retains the candidate answer when assessment fails and does not fabricate feedback", async () => {
    vi.mocked(interviewApi.assess).mockRejectedValue(
      new Error("Potential prompt injection detected"),
    );
    render(<MockInterview config={config} />);
    await start();
    fireEvent.change(screen.getByLabelText("Your answer"), {
      target: { value: "My answer" },
    });
    fireEvent.click(screen.getByRole("button", { name: "Review my answer" }));
    await screen.findByRole("alert");
    expect(
      (screen.getByLabelText("Your answer") as HTMLTextAreaElement).value,
    ).toBe("My answer");
    expect(screen.queryByText(judgment.modelAnswer)).toBeNull();
  });
  it("uses the interview UI in interview_mode and exposes no generation controls", async () => {
    vi.spyOn(interviewApi, "config").mockResolvedValue(config);
    render(<App />);
    await screen.findByText("What would you like to practice?");
    expect(screen.queryByRole("button", { name: "Run agents" })).toBeNull();
    expect(screen.queryByText("Company")).toBeNull();
  });
  it("shows mode-loading failures with a retry instead of guessing a mode", async () => {
    vi.spyOn(interviewApi, "config").mockRejectedValue(
      new Error("Backend unavailable"),
    );
    render(<App />);
    await screen.findByRole("alert");
    expect(
      screen.getByRole("button", { name: "Retry connection" }),
    ).toBeTruthy();
  });
});
