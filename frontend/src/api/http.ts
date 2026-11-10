const API_BASE_URL =
  import.meta.env.VITE_API_BASE_URL ?? "http://localhost:8080/api";
export async function request<T>(
  path: string,
  options: RequestInit = {},
  timeoutMs = 450000,
): Promise<T> {
  const controller = new AbortController();
  const timeout = window.setTimeout(() => controller.abort(), timeoutMs);
  try {
    const response = await fetch(`${API_BASE_URL}${path}`, {
      ...options,
      headers: {
        ...(options.body ? { "Content-Type": "application/json" } : {}),
        ...(path.startsWith("/question-bank/") &&
        options.method === "POST" &&
        generationAdminKey
          ? { "X-Admin-Key": generationAdminKey }
          : {}),
        ...options.headers,
      },
      signal: controller.signal,
    });
    if (!response.ok) {
      const error = await response
        .json()
        .catch(() => ({ message: response.statusText }));
      throw new Error(error.message ?? error.reason ?? "Request failed");
    }
    return (await response.json()) as T;
  } catch (error) {
    if (error instanceof DOMException && error.name === "AbortError")
      throw new Error("The AI request timed out. Please try again.");
    throw error;
  } finally {
    window.clearTimeout(timeout);
  }
}
export const setGenerationAdminKey = (key: string) => {
  generationAdminKey = key;
};
let generationAdminKey = "";
