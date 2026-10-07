import js from "@eslint/js";
import globals from "globals";
import tseslint from "typescript-eslint";

export default tseslint.config(
  {
    ignores: ["**/dist/**", "**/out/**", "**/node_modules/**", "packages/intellij/**", "**/.vscode-test/**"],
  },
  js.configs.recommended,
  ...tseslint.configs.recommended,
  {
    rules: {
      "@typescript-eslint/no-unused-vars": ["error", { argsIgnorePattern: "^_", varsIgnorePattern: "^_" }],
      "@typescript-eslint/no-explicit-any": "off",
    },
  },
  {
    files: ["**/*.mjs", "**/scripts/**", "**/test/**"],
    languageOptions: { globals: { ...globals.node } },
  },
  {
    files: ["packages/webview/**/*.{ts,tsx}"],
    languageOptions: { globals: { ...globals.browser } },
  },
);
