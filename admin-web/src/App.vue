<template><router-view /></template>

<style>
:root {
  color-scheme: light;
  --brand: #c94320; --brand-bright: #ff7547; --brand-soft: #fff0e8;
  --ink: #202939; --ink-secondary: #667085; --surface: #ffffff; --canvas: #f7f8fa;
  --line: #eaecf0; --shadow: 0 5px 24px rgba(23, 33, 52, .035);
  --el-color-primary: #c94320; --el-color-primary-light-3: #dc785b;
  --el-color-primary-light-5: #e7a28e; --el-color-primary-light-7: #f1c8bb;
  --el-color-primary-light-8: #f8ddd2; --el-color-primary-light-9: #fff0e8;
  --el-color-primary-dark-2: #a73517; --el-color-success: #168563;
  --el-color-warning: #a76714; --el-color-danger: #ca3947; --el-color-info: #667085;
  --el-text-color-primary: #202939; --el-text-color-regular: #475467; --el-text-color-secondary: #667085;
  --el-border-color: #e4e7ec; --el-border-color-light: #eaecf0; --el-border-color-lighter: #f0f1f4;
  --el-fill-color-light: #f8f9fb; --el-bg-color-page: #f7f8fa;
  --el-border-radius-base: 10px; --el-border-radius-small: 8px;
  --el-font-family: Inter, -apple-system, BlinkMacSystemFont, "Segoe UI", "PingFang SC", "Microsoft YaHei", sans-serif;
}
* { margin: 0; padding: 0; box-sizing: border-box; }
html, body, #app { min-height: 100%; font-family: var(--el-font-family); color: var(--ink); }
body { background: var(--canvas); -webkit-font-smoothing: antialiased; font-size: 14px; }
button, input { font: inherit; }
a { color: inherit; text-decoration: none; }
button:focus-visible, a:focus-visible { outline: 3px solid #e7a28e; outline-offset: 3px; }
::selection { background: #ffd7c3; }
.el-button { min-height: 36px; font-weight: 500; padding: 10px 16px; box-shadow: none; }
.el-button--small { min-height: 30px; padding: 6px 10px; }
.el-button--primary:not(.is-plain) { box-shadow: 0 3px 8px #c9432014; }
.el-input__wrapper, .el-select__wrapper { min-height: 40px; box-shadow: 0 0 0 1px var(--line) inset; }
.el-input__inner { font-size: 14px; }
.el-form-item__label { font-weight: 500; color: var(--ink); }
.el-table { --el-table-header-bg-color: #f8f9fb; --el-table-row-hover-bg-color: #fff7f2; --el-table-border-color: #edf0f3; border-radius: 12px; }
.el-table th.el-table__cell { color: #667085; font-size: 12px; font-weight: 600; padding: 13px 0; }
.el-table td.el-table__cell { padding: 16px 0; color: #344054; }
.el-table .cell { padding: 0 18px; }
.el-table__empty-block { min-height: 180px; }
.el-table .el-button { font-size: 12px; }
.el-tag { border-radius: 6px; font-weight: 500; }
.el-tabs__item { font-size: 14px; height: 48px; font-weight: 500; }
.el-tabs__nav-wrap::after { height: 1px; }
.el-tabs__header { margin-bottom: 24px; }
.el-pagination { justify-content: flex-end; padding: 20px 0 0; }
.el-card { border-radius: 18px; border: 1px solid var(--line); box-shadow: var(--shadow); }
.el-dialog { border-radius: 20px; padding: 28px; max-width: calc(100vw - 32px); }
.el-dialog__title { font-size: 20px; font-weight: 650; }
.el-dialog__header { padding-bottom: 24px; }
.el-message-box { border-radius: 18px; padding: 24px; }
.el-alert { border-radius: 10px; line-height: 1.6; }
.el-alert__title { font-size: 13px; }
.page-head { display: flex; align-items: flex-start; justify-content: space-between; gap: 20px; margin-bottom: 26px; flex-wrap: wrap; }
.page-head h1 { font-size: 27px; letter-spacing: -.7px; line-height: 1.35; font-weight: 700; }
.page-head p { color: var(--ink-secondary); font-size: 13px; line-height: 1.7; margin-top: 8px; }
.eyebrow { display: block; font-size: 10px; font-weight: 700; letter-spacing: 1.8px; color: var(--brand); margin-bottom: 9px; }
.panel { background: var(--surface); border: 1px solid var(--line); border-radius: 18px; padding: 24px; box-shadow: var(--shadow); min-width: 0; }
.panel-heading { display: flex; align-items: center; justify-content: space-between; gap: 16px; margin-bottom: 20px; }
.panel-heading h2 { font-size: 16px; font-weight: 650; }
.panel-heading p { color: var(--ink-secondary); font-size: 12px; margin-top: 5px; }
.filter-row { display: flex; align-items: center; gap: 12px; margin-bottom: 20px; flex-wrap: wrap; }
.filter-row .el-form-item { margin: 0; }
.table-caption { color: var(--ink-secondary); font-size: 12px; line-height: 1.6; }
.mono { font-variant-numeric: tabular-nums; }
.text-muted { color: var(--ink-secondary); }
.pill { display: inline-flex; align-items: center; gap: 6px; font-size: 11px; font-weight: 600; padding: 6px 10px; border-radius: 7px; background: #f2f4f7; color: #475467; }
.pill.brand { background: var(--brand-soft); color: var(--brand); }
.icon-button { display: inline-flex; align-items: center; justify-content: center; width: 38px; height: 38px; border: 1px solid var(--line); border-radius: 10px; background: white; color: var(--ink-secondary); cursor: pointer; }
.icon-button:hover { color: var(--brand); border-color: #f1c8bb; }
.button-content { display: inline-flex; align-items: center; gap: 7px; }
@media (max-width: 768px) {
  .page-head { margin-bottom: 20px; }
  .page-head h1 { font-size: 23px; }
  .panel { padding: 18px 14px; border-radius: 14px; }
  .el-table .cell { padding: 0 12px; }
  .filter-row .el-input { width: 100% !important; }
}
@media (prefers-reduced-motion: reduce) { *, *::before, *::after { animation-duration: .01ms !important; transition-duration: .01ms !important; } }
</style>
