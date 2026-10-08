import type { ThemeVar, ThemeVars } from "@xmlgridview/core/protocol";

/**
 * Maps the view's theme variables onto VS Code's --vscode-* variables. Values
 * are var() references, so the view follows theme changes without new messages.
 */
const MAP: Record<ThemeVar, string> = {
  "--xgv-bg": "var(--vscode-editor-background)",
  "--xgv-fg": "var(--vscode-editor-foreground)",
  "--xgv-muted-fg": "var(--vscode-descriptionForeground)",
  "--xgv-border": "var(--vscode-editorWidget-border, var(--vscode-panel-border, rgba(128,128,128,.35)))",
  "--xgv-header-bg": "var(--vscode-sideBarSectionHeader-background, var(--vscode-editorWidget-background))",
  "--xgv-header-fg": "var(--vscode-sideBarSectionHeader-foreground, var(--vscode-foreground))",
  "--xgv-hover-bg": "var(--vscode-list-hoverBackground)",
  "--xgv-selection-bg": "var(--vscode-list-activeSelectionBackground)",
  "--xgv-selection-fg": "var(--vscode-list-activeSelectionForeground, var(--vscode-foreground))",
  "--xgv-selection-inactive-bg": "var(--vscode-list-inactiveSelectionBackground)",
  "--xgv-focus-border": "var(--vscode-focusBorder)",
  "--xgv-input-bg": "var(--vscode-input-background)",
  "--xgv-input-fg": "var(--vscode-input-foreground)",
  "--xgv-input-border": "var(--vscode-input-border, var(--vscode-editorWidget-border, transparent))",
  "--xgv-button-bg": "var(--vscode-button-background)",
  "--xgv-button-fg": "var(--vscode-button-foreground)",
  "--xgv-button-hover-bg": "var(--vscode-button-hoverBackground)",
  "--xgv-link-fg": "var(--vscode-textLink-foreground)",
  "--xgv-match-bg": "var(--vscode-editor-findMatchHighlightBackground, rgba(234,92,0,.33))",
  "--xgv-match-current-bg": "var(--vscode-editor-findMatchBackground, rgba(234,92,0,.6))",
  "--xgv-warning-bg": "var(--vscode-inputValidation-warningBackground, var(--vscode-editorWarning-background, rgba(255,200,0,.2)))",
  "--xgv-warning-fg": "var(--vscode-foreground)",
  "--xgv-error-fg": "var(--vscode-errorForeground)",
  "--xgv-tag-fg": "var(--vscode-symbolIcon-fieldForeground, var(--vscode-editor-foreground))",
  "--xgv-attr-fg": "var(--vscode-descriptionForeground)",
  "--xgv-json-key-fg": "var(--vscode-debugTokenExpression-name, var(--vscode-editor-foreground))",
  "--xgv-json-string-fg": "var(--vscode-debugTokenExpression-string, var(--vscode-editor-foreground))",
  "--xgv-json-number-fg": "var(--vscode-debugTokenExpression-number, var(--vscode-editor-foreground))",
  "--xgv-json-keyword-fg": "var(--vscode-debugTokenExpression-boolean, var(--vscode-editor-foreground))",
  "--xgv-font-family": "var(--vscode-font-family)",
  "--xgv-font-size": "var(--vscode-font-size)",
  "--xgv-mono-font-family": "var(--vscode-editor-font-family)",
  "--xgv-mono-font-size": "var(--vscode-editor-font-size)",
};

export function themeVars(): ThemeVars {
  return { ...MAP };
}
