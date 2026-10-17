import { createTaskStreams } from './taskEvents.js'

// 后台任务流是全局共享的：库页与 Agent 页共用同一份注册表，
// 这样在 Agent 页跑任务时，库页的「后台任务」角标同样能看到。
const listeners = new Set()

export const taskStreams = createTaskStreams({
  onActiveChange: active => {
    for (const listener of listeners) listener(active)
  }
})

/** 订阅「当前有哪些视频在跑任务」，返回取消订阅函数。 */
export function onActiveTasksChange(listener) {
  listeners.add(listener)
  return () => listeners.delete(listener)
}
