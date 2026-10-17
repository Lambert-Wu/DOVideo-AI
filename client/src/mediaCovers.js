/**
 * 资料库卡片封面（视频第一帧）的补抓逻辑。
 *
 * 列表接口对还没抽过封面的视频返回 coverUrl = null；后端是收到请求才现用 ffmpeg 抽帧，
 * 所以这里按有限并发补，避免一次把 ffmpeg 全打满、慢网络下也一起超时。
 */

/** 需要补抽封面的条目：没有封面，且此前没失败过（失败的不再反复重试）。 */
export function pickCoverCandidates(items) {
  if (!Array.isArray(items)) return []
  return items.filter(item => item && item.id != null && !item.coverUrl && !item.coverFailed)
}

/**
 * 为缺失封面的条目补抓首帧，结果原地写回条目（coverUrl / coverFailed）。
 *
 * @param items 列表条目，会被就地修改
 * @param request id => Promise<url|null>，抽成参数便于测试
 * @param options.concurrency 并发上限
 * @returns 本轮真正尝试过的条目
 */
export async function loadMissingCovers(items, request, { concurrency = 3 } = {}) {
  const pending = pickCoverCandidates(items)
  if (!pending.length) return pending

  let cursor = 0
  const worker = async () => {
    while (cursor < pending.length) {
      const item = pending[cursor]
      cursor += 1
      let url = null
      try {
        url = await request(item.id)
      } catch {
        url = null
      }
      if (url) item.coverUrl = url
      else item.coverFailed = true
    }
  }

  await Promise.all(Array.from({ length: Math.max(1, Math.min(concurrency, pending.length)) }, worker))
  return pending
}
