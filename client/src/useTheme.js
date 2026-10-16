import { ref } from 'vue'

// 主题是跨视图共享的：模块级单例，避免库页与 Agent 页各持一份状态。
const THEME_KEY = 'dovideo-theme'
const theme = ref('light')

export function applyTheme(value) {
  const next = value === 'dark' ? 'dark' : 'light'
  theme.value = next
  if (typeof document === 'undefined') return
  if (next === 'dark') document.documentElement.setAttribute('data-theme', 'dark')
  else document.documentElement.removeAttribute('data-theme')
  const meta = document.querySelector('meta[name="theme-color"]')
  if (meta) meta.setAttribute('content', next === 'dark' ? '#0b0c10' : '#f4f5f6')
}

export function toggleTheme() {
  const next = theme.value === 'light' ? 'dark' : 'light'
  applyTheme(next)
  try {
    localStorage.setItem(THEME_KEY, next)
  } catch {
    // 隐私模式下写入会失败，忽略即可，主题在当前会话内仍生效。
  }
}

/** index.html 的内联脚本已在首屏前设好 data-theme，挂载时同步到共享状态。 */
export function syncThemeFromDocument() {
  if (typeof document === 'undefined') return
  applyTheme(document.documentElement.getAttribute('data-theme') === 'dark' ? 'dark' : 'light')
}

export function useTheme() {
  return { theme, applyTheme, toggleTheme, syncThemeFromDocument }
}
