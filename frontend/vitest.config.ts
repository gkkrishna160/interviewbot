import { defineConfig } from 'vitest/config';
import react from '@vitejs/plugin-react';

export default defineConfig({
  cacheDir: 'node_modules/.vite-test',
  plugins: [react()],
  test: { environment: 'jsdom', pool: 'threads', include: ['tests/**/*.test.tsx'], restoreMocks: true },
});
