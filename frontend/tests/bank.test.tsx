import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import {
  cleanup,
  fireEvent,
  render,
  screen,
  waitFor,
} from "@testing-library/react";
import { QuestionBank } from "../src/pages/QuestionBank";
import { bankApi, BankQuestion, Category, Dashboard } from "../src/api/bankApi";

afterEach(cleanup);
const question: BankQuestion = {
  id: 1,
  text: "How would you diagnose a stale read in a Java service with unsynchronized shared state?",
  categoryId: 2,
  category: "Backend / Java / Spring Boot",
  topicId: 1,
  topic: "Core Java",
  difficulty: "INTERMEDIATE",
  questionType: "DEBUGGING",
  tags: ["threads"],
  status: "APPROVED",
  source: "AI_GENERATED",
  qualityScore: 92,
  relevanceScore: 90,
  reviewAttempts: 1,
  createdAt: "2026-11-08T00:00:00Z",
  updatedAt: "2026-11-08T00:00:00Z",
};
const categories: Category[] = [
  {
    id: 2,
    name: "Backend / Java / Spring Boot",
    topics: [
      {
        id: 1,
        categoryId: 2,
        category: "Backend / Java / Spring Boot",
        name: "Core Java",
        approved: 1,
        pending: 0,
        deficit: 14,
      },
    ],
  },
  { id: 3, name: "Frontend / React", topics: [] },
  {
    id: 4,
    name: "AI Engineering",
    topics: [
      {
        id: 37,
        categoryId: 4,
        category: "AI Engineering",
        name: "Agentic AI Engineering",
        approved: 0,
        pending: 0,
        deficit: 15,
      },
    ],
  },
];
const dashboard: Dashboard = {
  counts: { APPROVED: 1, PENDING_REVIEW: 0, REJECTED: 0, DUPLICATE: 0 },
  generated: 1,
  reviewFailures: 0,
  coverage: categories[0].topics,
  schedulingEnabled: false,
  provider: "ollama",
  model: "test",
};
beforeEach(() => {
  vi.spyOn(bankApi, "categories").mockResolvedValue(categories);
  vi.spyOn(bankApi, "dashboard").mockResolvedValue(dashboard);
  vi.spyOn(bankApi, "activity").mockResolvedValue([]);
  vi.spyOn(bankApi, "questions").mockResolvedValue({
    content: [question],
    totalElements: 1,
    totalPages: 1,
    page: 0,
  });
  HTMLDialogElement.prototype.showModal = vi.fn(function (
    this: HTMLDialogElement,
  ) {
    this.setAttribute("open", "");
  });
});
describe("Question bank", () => {
  it("opens with three separate banks and scopes browsing to the chosen bank", async () => {
    render(<QuestionBank onPractice={vi.fn()} />);
    const ai = await screen.findByRole("button", {
      name: "Browse AI Engineering →",
    });
    expect(
      screen.getByRole("button", { name: "Browse Frontend / React →" }),
    ).toBeTruthy();
    expect(
      screen.getByRole("button", {
        name: "Browse Backend / Java / Spring Boot →",
      }),
    ).toBeTruthy();
    expect(bankApi.questions).not.toHaveBeenCalled();
    fireEvent.click(ai);
    await waitFor(() =>
      expect(
        vi.mocked(bankApi.questions).mock.lastCall![0].get("categoryId"),
      ).toBe("4"),
    );
    expect(
      screen.getByRole("option", { name: "Agentic AI Engineering" }),
    ).toBeTruthy();
    expect(screen.queryByRole("option", { name: "Core Java" })).toBeNull();
    fireEvent.click(screen.getByRole("button", { name: "Reset filters" }));
    await screen.findByRole("button", { name: question.text });
    expect(vi.mocked(bankApi.questions).mock.calls.length).toBeGreaterThan(1);
    await waitFor(() =>
      expect(
        vi.mocked(bankApi.questions).mock.lastCall![0].get("categoryId"),
      ).toBe("4"),
    );
  });
  it("defaults to approved questions and preserves access to interview practice", async () => {
    const practice = vi.fn();
    render(<QuestionBank onPractice={practice} />);
    fireEvent.click(
      await screen.findByRole("button", {
        name: "Browse Backend / Java / Spring Boot →",
      }),
    );
    await screen.findByRole("button", { name: question.text });
    expect(bankApi.questions).toHaveBeenCalledWith(expect.objectContaining({}));
    expect(vi.mocked(bankApi.questions).mock.calls[0][0].get("status")).toBe(
      "APPROVED",
    );
    fireEvent.click(
      screen.getByRole("button", { name: "Start practicing ↗" }),
    );
    expect(practice).toHaveBeenCalledOnce();
  });
  it("sends topic, difficulty and fundamental versus system-design filters to the API", async () => {
    render(<QuestionBank onPractice={vi.fn()} />);
    fireEvent.click(
      await screen.findByRole("button", {
        name: "Browse Backend / Java / Spring Boot →",
      }),
    );
    await screen.findByRole("button", { name: question.text });
    fireEvent.change(screen.getByLabelText("Topic"), {
      target: { value: "1" },
    });
    fireEvent.change(screen.getByLabelText("Difficulty"), {
      target: { value: "ADVANCED" },
    });
    fireEvent.change(screen.getByLabelText("Question type"), {
      target: { value: "SYSTEM_DESIGN" },
    });
    await waitFor(() => {
      const params = vi.mocked(bankApi.questions).mock.lastCall![0];
      expect(params.get("topicId")).toBe("1");
      expect(params.get("difficulty")).toBe("ADVANCED");
      expect(params.get("questionType")).toBe("SYSTEM_DESIGN");
    });
  });
  it("shows review audit details and allows an explicit re-review request", async () => {
    const detail = {
      question,
      validations: [
        {
          id: 1,
          previousStatus: "PENDING_REVIEW" as const,
          decision: "APPROVED" as const,
          reason: "Concrete context and correct visibility semantics.",
          reviewer: "question_validation",
          model: "test",
          createdAt: question.createdAt,
        },
      ],
    };
    vi.spyOn(bankApi, "detail").mockResolvedValue(detail);
    const requeue = vi.spyOn(bankApi, "requeue").mockResolvedValue({
      ...detail,
      question: { ...question, status: "PENDING_REVIEW", reviewAttempts: 0 },
    });
    render(<QuestionBank onPractice={vi.fn()} />);
    fireEvent.click(
      await screen.findByRole("button", {
        name: "Browse Backend / Java / Spring Boot →",
      }),
    );
    fireEvent.click(await screen.findByRole("button", { name: question.text }));
    await screen.findByText(
      "Concrete context and correct visibility semantics.",
    );
    fireEvent.change(screen.getByLabelText("Reason for another review"), {
      target: { value: "Check the latest Java memory model guidance" },
    });
    fireEvent.click(screen.getByRole("button", { name: "Request re-review" }));
    await waitFor(() =>
      expect(requeue).toHaveBeenCalledWith(
        1,
        "Check the latest Java memory model guidance",
      ),
    );
  });
  it("starts a real backend cycle through the activity action and displays the receipt", async () => {
    const run = vi
      .spyOn(bankApi, "run")
      .mockResolvedValue({ accepted: true, executionId: 42 });
    render(<QuestionBank onPractice={vi.fn()} />);
    fireEvent.click(
      await screen.findByRole("button", {
        name: "Browse Backend / Java / Spring Boot →",
      }),
    );
    await screen.findByRole("button", { name: question.text });
    fireEvent.click(
      screen.getAllByRole("button", { name: "Agent activity" })[0],
    );
    fireEvent.click(screen.getByRole("button", { name: "Run agents" }));
    await screen.findByText(/Agent cycle #42 started/);
    expect(run).toHaveBeenCalledOnce();
  });
  it("keeps provider failures visible without manufacturing questions", async () => {
    vi.mocked(bankApi.questions).mockRejectedValue(
      new Error("Database unavailable"),
    );
    render(<QuestionBank onPractice={vi.fn()} />);
    fireEvent.click(
      await screen.findByRole("button", {
        name: "Browse Backend / Java / Spring Boot →",
      }),
    );
    await screen.findByText("Database unavailable");
    expect(screen.queryByRole("button", { name: question.text })).toBeNull();
  });
});
