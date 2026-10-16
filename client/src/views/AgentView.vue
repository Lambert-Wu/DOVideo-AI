<template>
  <div class="agent-page">
    <header class="agent-topbar">
      <button class="agent-back" type="button" @click="goBack">
        <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><line x1="19" y1="12" x2="5" y2="12"></line><polyline points="12 19 5 12 12 5"></polyline></svg>
        返回
      </button>
      <div class="agent-topbar-title" :title="sidebar.title">{{ sidebar.title || 'Video Agent' }}</div>
      <button
          class="theme-btn"
          type="button"
          :title="theme === 'light' ? '切换到深色主题' : '切换到亮色主题'"
          :aria-label="theme === 'light' ? '切换到深色主题' : '切换到亮色主题'"
          @click="toggleTheme"
      >
        <svg v-if="theme === 'light'" width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M21 12.79A9 9 0 1 1 11.21 3 7 7 0 0 0 21 12.79z"></path></svg>
        <svg v-else width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><circle cx="12" cy="12" r="4"></circle><path d="M12 2v2"></path><path d="M12 20v2"></path><path d="m4.93 4.93 1.41 1.41"></path><path d="m17.66 17.66 1.41 1.41"></path><path d="M2 12h2"></path><path d="M20 12h2"></path><path d="m6.34 17.66-1.41 1.41"></path><path d="m19.07 4.93-1.41 1.41"></path></svg>
      </button>
    </header>

    <div class="agent-body">
      <!-- 左：原视频 -->
      <section class="agent-col agent-col-video" aria-label="原视频">
        <div class="video-evidence">
          <video
              v-if="sidebar.playbackUrl"
              ref="videoPlayer"
              :src="sidebar.playbackUrl"
              controls
              playsinline
              preload="metadata"
              @loadedmetadata="applyPendingSeek"
              @error="handlePlaybackError"
          ></video>
          <div v-else-if="sidebar.playbackLoading" class="video-evidence-loading">正在载入原视频...</div>
          <div v-else-if="sidebar.playbackError" class="video-evidence-error" role="alert">
            <span>{{ sidebar.playbackError }}</span>
            <button type="button" @click="retryPlayback">重新加载</button>
          </div>
          <p v-if="sidebar.playbackUrl">点击右侧结果中的时间戳，可跳转到对应画面</p>
        </div>
        <div v-if="sidebar.mode !== 'compose' && !sidebar.loading" class="result-actions agent-video-actions">
          <button type="button" @click="startNewAnalysis">更换产物</button>
          <button type="button" :disabled="!sidebar.content" @click="copyResult">复制结果</button>
          <button type="button" :disabled="!sidebar.content" @click="downloadResult">导出 Markdown</button>
        </div>
      </section>

      <!-- 右：分析结果 -->
      <section class="agent-col agent-col-result" aria-label="分析结果">
        <div ref="sidebarBody" class="agent-scroll">
          <div v-if="sidebar.mode === 'compose'" class="agent-composer">
            <p class="agent-caption">选择分析模式（决定产物形态）</p>
            <div class="goal-presets agent-mode-row">
              <button
                  v-for="m in analysisModes"
                  :key="m.value"
                  :class="{ active: sidebar.analysisMode === m.value }"
                  @click="sidebar.analysisMode = m.value"
              >
                <strong>{{ m.title }}</strong>
                <span>{{ m.description }}</span>
              </button>
            </div>
            <p class="agent-caption">告诉 Agent 你希望从视频中得到什么产物</p>
            <p v-if="sidebar.error" class="inline-error" role="alert">{{ sidebar.error }}</p>
            <textarea
                v-model="sidebar.goal"
                aria-label="分析目标"
                maxlength="500"
                placeholder="例如：梳理核心观点，给出带时间戳的证据和可执行建议（Ctrl / ⌘ + Enter 提交）"
                @keydown.ctrl.enter.prevent="submitAgent"
                @keydown.meta.enter.prevent="submitAgent"
            ></textarea>
            <p v-if="sidebar.goal.length > 400" class="field-counter">已输入 {{ sidebar.goal.length }} / 500 字</p>
            <div class="goal-presets">
              <button
                  v-for="preset in goalPresets"
                  :key="preset.title"
                  :class="{ active: sidebar.goal === preset.prompt }"
                  @click="sidebar.goal = preset.prompt"
              >
                <strong>{{ preset.title }}</strong>
                <span>{{ preset.description }}</span>
              </button>
            </div>
            <button class="agent-run-btn" :disabled="!sidebar.goal.trim()" @click="submitAgent">
              {{ sidebar.error ? '重新分析' : '开始分析' }}
            </button>
          </div>

          <div v-else-if="sidebar.loading" class="agent-running">
            <div class="loading-state">
              <div class="quantum-loader small"></div>
              <p aria-live="polite">{{ loadingHeadline }}</p>
              <p v-if="sidebar.streamOffline" class="stream-offline" role="status">
                连接中断，正在自动重连（第 {{ sidebar.streamRetry }} 次）· 任务仍在服务端继续
              </p>
              <p class="loading-hint">可以随时返回资料库，任务会在后台继续</p>
            </div>
            <div v-if="sidebar.plan?.tasks?.length" class="agent-meta-block">
              <span class="meta-label">任务计划</span>
              <ol><li v-for="task in sidebar.plan.tasks" :key="task">{{ task }}</li></ol>
            </div>
            <div v-if="traceStages.length" class="agent-meta-block">
              <span class="meta-label">已完成阶段</span>
              <div class="stage-list"><span v-for="stage in traceStages" :key="stage[0]">{{ stage[0] }} · {{ stage[1] }}</span></div>
            </div>
          </div>

          <div v-else>
            <p v-if="sidebar.error" class="inline-error" role="alert">{{ sidebar.error }}</p>
            <div class="evidence-search">
              <div class="evidence-search-form">
                <input
                    v-model="sidebar.evidenceQuery"
                    aria-label="视频证据检索"
                    maxlength="500"
                    placeholder="定位 PPT、字幕、代码或某段讲解"
                    @keyup.enter="searchEvidence"
                />
                <button type="button" :disabled="sidebar.evidenceLoading || !sidebar.evidenceQuery.trim()" @click="searchEvidence">
                  {{ sidebar.evidenceLoading ? '检索中' : '定位证据' }}
                </button>
              </div>
              <p v-if="sidebar.evidenceError" class="evidence-search-error" aria-live="polite">{{ sidebar.evidenceError }}</p>
              <div v-if="sidebar.evidenceResults.length" class="evidence-search-results" aria-live="polite">
                <button
                    v-for="hit in sidebar.evidenceResults"
                    :key="`${hit.startMs}-${hit.endMs}`"
                    type="button"
                    :title="hit.snippet || '该时间段暂无可展示文本'"
                    @click="seekToEvidence(hit.startMs)"
                >
                  <strong>{{ formatEvidenceTime(hit.startMs) }}</strong>
                  <small>{{ hit.source || '视频证据' }}</small>
                  <span>{{ hit.snippet || '该时间段暂无可展示文本' }}</span>
                </button>
              </div>
            </div>
            <div class="markdown-content" v-html="renderedMarkdown" @click="seekEvidence"></div>
            <details v-if="sidebar.plan?.tasks?.length || traceStages.length" class="agent-inspector">
              <summary>分析详情</summary>
              <div class="agent-inspector-content">
                <div v-if="sidebar.plan?.tasks?.length" class="agent-meta-block">
                  <span class="meta-label">Planner 任务</span>
                  <div v-if="sidebar.editingPlan" class="plan-editor">
                    <div v-for="(_, index) in sidebar.planDraft" :key="index" class="plan-editor-row">
                      <input v-model="sidebar.planDraft[index]" maxlength="500" :aria-label="`任务 ${index + 1}`" />
                      <button type="button" title="删除任务" @click="removePlanTask(index)">×</button>
                    </div>
                    <button v-if="sidebar.planDraft.length < 5" type="button" @click="addPlanTask">添加任务</button>
                    <div class="plan-editor-actions">
                      <button type="button" @click="cancelPlanEdit">取消</button>
                      <button type="button" :disabled="sidebar.rerunLoading" @click="rerunWithPlan">
                        {{ sidebar.rerunLoading ? '提交中' : '按新计划重跑' }}
                      </button>
                    </div>
                  </div>
                  <template v-else>
                    <ol><li v-for="task in sidebar.plan.tasks" :key="task">{{ task }}</li></ol>
                    <button type="button" class="plan-edit-trigger" @click="startPlanEdit">调整计划</button>
                  </template>
                </div>
                <div v-if="traceStages.length" class="agent-meta-block">
                  <span class="meta-label">执行轨迹</span>
                  <div class="stage-list"><span v-for="stage in traceStages" :key="stage[0]">{{ stage[0] }} · {{ stage[1] }}</span></div>
                </div>
                <div v-if="sidebar.evaluation && Object.keys(sidebar.evaluation).length" class="quality-row">
                  <span>结构完整 {{ sidebar.evaluation.structuredValid ? '通过' : '待完善' }}</span>
                  <span>证据支持 {{ formatPercent(sidebar.evaluation.evidenceSupportRate) }}</span>
                  <span>Critic {{ sidebar.evaluation.criticPassed ? '通过' : '达到轮次上限' }}</span>
                </div>
              </div>
            </details>
            <div class="follow-up-box">
              <textarea
                  v-model="sidebar.followUp"
                  aria-label="基于视频继续追问"
                  maxlength="500"
                  placeholder="基于视频继续追问...（Ctrl / ⌘ + Enter 发送）"
                  @keydown.ctrl.enter.prevent="submitFollowUp"
                  @keydown.meta.enter.prevent="submitFollowUp"
              ></textarea>
              <button :disabled="sidebar.followUpLoading || !sidebar.followUp.trim()" @click="submitFollowUp">
                {{ sidebar.followUpLoading ? '分析中' : '追问' }}
              </button>
            </div>
            <div class="feedback-row">
              <span>这个结果有帮助吗？</span>
              <button :disabled="sidebar.feedbackLoading" :class="{ active: sidebar.feedback === 1 }" :aria-pressed="sidebar.feedback === 1" @click="sendFeedback(1)" title="有帮助">赞</button>
              <button :disabled="sidebar.feedbackLoading" :class="{ active: sidebar.feedback === -1 }" :aria-pressed="sidebar.feedback === -1" @click="sendFeedback(-1)" title="需改进">踩</button>
            </div>
          </div>
        </div>
      </section>
    </div>

    <transition name="toast-pop">
      <div
          v-if="message"
          class="notification-bar agent-toast"
          :class="{ error: messageIsError }"
          :role="messageIsError ? 'alert' : 'status'"
          :title="messageIsError ? '点击关闭这条提示' : null"
          @click="dismissMessage"
      >
        {{ message }}
      </div>
    </transition>
  </div>
</template>

<script setup>
import { computed, nextTick, onMounted, onUnmounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { apiRequest } from '../api.js'
import { DEMO_ITEM } from '../demoData.js'
import { findMediaItem as findCachedMediaItem } from '../mediaCache.js'
import { taskStreams } from '../taskStreams.js'
import { useAnalysisWorkspace } from '../useAnalysisWorkspace.js'
import { useTheme } from '../useTheme.js'

const route = useRoute()
const router = useRouter()
const { theme, toggleTheme } = useTheme()

const mediaId = Number(route.params.id)
const DEMO_MODE = route.query.demo !== undefined
  || new URLSearchParams(window.location.search).has('demo')

// --- 轻量提示条（原先在库页的 notification-bar，这里是本页自己的）---
const MESSAGE_TIMEOUT_MS = 4000
const message = ref('')
const messageIsError = ref(false)
let messageTimer = null
const showMessage = (msg, isError = false) => {
  clearTimeout(messageTimer)
  messageTimer = null
  message.value = msg
  messageIsError.value = isError
  if (isError) return
  messageTimer = setTimeout(() => {
    if (message.value !== msg) return
    message.value = ''
    messageIsError.value = false
  }, MESSAGE_TIMEOUT_MS)
}
const dismissMessage = () => {
  if (!messageIsError.value) return
  clearTimeout(messageTimer)
  messageTimer = null
  message.value = ''
  messageIsError.value = false
}

const videoPlayer = ref(null)
const sidebarBody = ref(null)
const mediaItem = ref(null)
const pendingSeek = ref(null)
const elapsedSeconds = ref(0)
let elapsedTimer = null
const findMediaItem = id => (String(mediaItem.value?.id) === String(id) ? mediaItem.value : null)

const {
  sidebar,
  goalPresets,
  analysisModes,
  traceStages,
  renderedMarkdown,
  openAgent,
  submitAgent,
  startNewAnalysis,
  showDemoResult,
  startPlanEdit,
  cancelPlanEdit,
  addPlanTask,
  removePlanTask,
  rerunWithPlan,
  submitFollowUp,
  searchEvidence,
  sendFeedback,
  retryPlayback,
  handlePlaybackError,
  resetWorkspace,
  formatPercent
} = useAnalysisWorkspace({
  demoMode: DEMO_MODE,
  taskStreams,
  showMessage,
  refreshMediaList: async () => {},
  findMediaItem,
  onAnswerAppended: () => scrollToLatestAnswer()
})

const elapsedLabel = computed(() => {
  const total = elapsedSeconds.value
  if (total < 1) return ''
  const minutes = String(Math.floor(total / 60)).padStart(2, '0')
  return `${minutes}:${String(total % 60).padStart(2, '0')}`
})

const loadingHeadline = computed(() => {
  const headline = sidebar.value.statusMessage || 'Agent 正在分析视频证据'
  return elapsedLabel.value ? `${headline} · 已等待 ${elapsedLabel.value}` : headline
})

// --- 返回资料库 ---
const goBack = () => {
  if (window.history.state?.back) router.back()
  else router.push({ name: 'library' })
}

// --- 原视频 ---
const seekPlayerTo = seconds => {
  const player = videoPlayer.value
  if (!player) return
  const duration = player.duration
  const maxTime = Number.isFinite(duration) ? Math.max(0, duration - 0.1) : Number.MAX_SAFE_INTEGER
  player.currentTime = Math.min(Math.max(0, seconds), maxTime)
  player.play().catch(() => {})
  player.scrollIntoView({ behavior: 'smooth', block: 'nearest' })
}

/** metadata 就绪后应用挂起的跳转（用户可能在原视频还没载入时就点了时间戳）。 */
const applyPendingSeek = () => {
  const player = videoPlayer.value
  if (!player || pendingSeek.value === null) return
  if (player.readyState === 0) return // 等 @loadedmetadata 再次触发
  const seconds = pendingSeek.value
  pendingSeek.value = null
  seekPlayerTo(seconds)
}

// --- 时间戳跳转：metadata 就绪前先记下目标位置，就绪后自动跳 ---
const seekVideo = seconds => {
  if (!Number.isFinite(seconds)) return
  const player = videoPlayer.value
  if (player && player.readyState > 0) {
    seekPlayerTo(seconds)
    return
  }
  if (sidebar.value.playbackError) {
    showMessage('原视频加载失败，可先点“重新加载”', true)
    return
  }
  pendingSeek.value = seconds
}

const seekEvidence = event => {
  const link = event.target.closest('a[href^="#video-t="]')
  if (!link) return
  event.preventDefault()
  seekVideo(Number(link.getAttribute('href').split('=')[1]))
}

const seekToEvidence = timestampMs => seekVideo(Number(timestampMs) / 1000)

const formatEvidenceTime = timestampMs => {
  const seconds = Math.max(0, Math.floor(Number(timestampMs) / 1000))
  const hours = Math.floor(seconds / 3600)
  const minutes = Math.floor((seconds % 3600) / 60)
  const time = `${String(minutes).padStart(2, '0')}:${String(seconds % 60).padStart(2, '0')}`
  return hours ? `${String(hours).padStart(2, '0')}:${time}` : time
}

const scrollToLatestAnswer = async () => {
  await nextTick()
  const container = sidebarBody.value?.querySelector('.markdown-content')
  if (!container) return
  const headings = container.querySelectorAll('h2, h3')
  const anchor = headings.length ? headings[headings.length - 1] : container.lastElementChild
  anchor?.scrollIntoView({ behavior: 'smooth', block: 'start' })
}

// --- 复制 / 导出 ---
const copyToClipboard = async text => {
  try {
    await navigator.clipboard.writeText(text)
    return true
  } catch {
    // 继续走下面的降级方案。
  }
  try {
    const scratch = document.createElement('textarea')
    scratch.value = text
    scratch.setAttribute('readonly', '')
    scratch.style.position = 'fixed'
    scratch.style.top = '0'
    scratch.style.opacity = '0'
    document.body.appendChild(scratch)
    scratch.select()
    const copied = document.execCommand('copy')
    document.body.removeChild(scratch)
    return copied
  } catch {
    return false
  }
}

const copyResult = async () => {
  const content = sidebar.value.content
  if (!content) {
    showMessage('还没有可复制的内容', true)
    return
  }
  if (await copyToClipboard(content)) showMessage('分析结果已复制')
  else showMessage('复制失败，请手动选中内容后复制', true)
}

const downloadResult = () => {
  const content = sidebar.value.content
  if (!content) {
    showMessage('还没有可导出的内容', true)
    return
  }
  const title = sidebar.value.title || ''
  const raw = title.split(' · ').slice(1).join(' · ') || title
  const cleaned = raw.replace(/\.[^/.]+$/, '').replace(/[\\/:*?"<>|]/g, '_').trim()
  const blob = new Blob([content], { type: 'text/markdown;charset=utf-8' })
  const url = URL.createObjectURL(blob)
  const link = document.createElement('a')
  link.href = url
  link.download = `${cleaned || 'analysis'}.md`
  document.body.appendChild(link)
  link.click()
  document.body.removeChild(link)
  // 立刻 revoke 在部分浏览器会导致下载拿不到内容，延后一拍更稳。
  setTimeout(() => URL.revokeObjectURL(url), 0)
  showMessage(`已导出 ${link.download}`)
}

const handleAuthExpired = () => {
  showMessage('登录已过期，请重新登录', true)
  router.replace({ name: 'library' })
}

onMounted(async () => {
  window.addEventListener('auth-expired', handleAuthExpired)
  elapsedTimer = setInterval(() => { elapsedSeconds.value += 1 }, 1000)

  if (DEMO_MODE) {
    mediaItem.value = { ...DEMO_ITEM, id: mediaId }
    await openAgent(mediaItem.value)
    showDemoResult()
    return
  }

  // 先用资料库的进程内缓存取文件名（进入本页前通常刚在库页加载过），拿不到再回退请求，
  // 省掉一次隧道往返。
  let filename = findCachedMediaItem(mediaId)?.filename || ''
  if (!filename) {
    try {
      const response = await apiRequest(`/media/list?_t=${Date.now()}`)
      if (response.ok) {
        const items = await response.json()
        const found = Array.isArray(items) ? items.find(item => String(item.id) === String(mediaId)) : null
        if (found?.filename) filename = found.filename
      }
    } catch (error) {
      console.warn('media list unavailable', error)
    }
  }
  mediaItem.value = { id: mediaId, filename: filename || `视频 #${mediaId}` }
  await openAgent(mediaItem.value)
})

onUnmounted(() => {
  window.removeEventListener('auth-expired', handleAuthExpired)
  clearInterval(elapsedTimer)
  clearTimeout(messageTimer)
  if (sidebar.value.mediaId) taskStreams.stopMedia(sidebar.value.mediaId)
  resetWorkspace()
})
</script>

<style>
/* Agent 独立页：顶部返回栏 + 左视频 / 右结果 双栏。 */
.agent-page {
  display: flex;
  flex-direction: column;
  height: 100vh;
  background: var(--bg-deep);
  color: var(--text-main);
  font-family: 'Space Grotesk', 'Noto Sans SC', monospace;
}

.agent-topbar {
  display: flex;
  align-items: center;
  gap: 14px;
  padding: 12px 20px;
  border-bottom: 1px solid var(--border-tech);
  background: var(--bg-nav);
  backdrop-filter: blur(12px);
  flex-shrink: 0;
}

.agent-back {
  display: flex;
  align-items: center;
  gap: 6px;
  border: 1px solid var(--border-tech);
  border-radius: 4px;
  background: transparent;
  color: var(--text-main);
  padding: 6px 12px;
  font: inherit;
  font-size: 0.85rem;
  cursor: pointer;
  transition: all 0.3s;
}
.agent-back:hover { border-color: var(--accent); color: var(--accent); }

.agent-topbar-title {
  flex: 1;
  min-width: 0;
  font-weight: 700;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.agent-body {
  flex: 1;
  min-height: 0;
  display: grid;
  grid-template-columns: minmax(0, 1fr) minmax(0, 1fr);
  gap: 1px;
  background: var(--border-tech);
}

.agent-col { min-width: 0; min-height: 0; background: var(--bg-card); display: flex; flex-direction: column; }

.agent-col-video { overflow-y: auto; }
.agent-col-video .video-evidence { margin: 0; border-bottom: 0; }

/* 原视频按需载入时的占位 */
.video-evidence-placeholder {
  min-height: 220px;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 14px;
  padding: 24px;
  color: var(--text-sub);
  text-align: center;
}
.video-evidence-placeholder button {
  border: 1px solid var(--accent);
  border-radius: 4px;
  background: var(--accent-soft-strong);
  color: var(--accent);
  padding: 8px 18px;
  font: inherit;
  cursor: pointer;
  transition: all 0.3s;
}
.video-evidence-placeholder button:hover { background: var(--accent); color: var(--accent-contrast); }

/* 结果操作放在视频下方（原在右栏顶部） */.agent-video-actions {
  justify-content: flex-start;
  flex-wrap: wrap;
  margin: 0;
  padding: 14px 20px;
  border-top: 1px solid var(--border-tech);
}

.agent-scroll { flex: 1; min-height: 0; overflow-y: auto; padding: 26px 30px; }

.agent-toast {
  position: fixed;
  left: 50%;
  bottom: 32px;
  transform: translateX(-50%);
  margin-top: 0;
  z-index: 2000;
  max-width: min(560px, 90vw);
}

@media (max-width: 900px) {
  .agent-body { grid-template-columns: 1fr; grid-template-rows: auto minmax(0, 1fr); }
  .agent-col-video { max-height: 42vh; }
  .agent-scroll { padding: 20px 16px; }
  .agent-topbar { padding: 10px 12px; gap: 10px; }
}
</style>
