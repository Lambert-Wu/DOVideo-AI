import assert from 'node:assert/strict'
import test from 'node:test'
import { loadMissingCovers, pickCoverCandidates } from './mediaCovers.js'

test('已有封面或已失败过的条目不重复请求', () => {
  const items = [
    { id: 1, coverUrl: '/minio/x.jpg' },
    { id: 2, coverUrl: null },
    { id: 3, coverFailed: true },
    { id: 4 }
  ]
  assert.deepEqual(pickCoverCandidates(items).map(item => item.id), [2, 4])
  assert.equal(pickCoverCandidates(items).length, 2)
})

test('抓到地址写回条目，抓不到标记失败以便下一轮不再重试', async () => {
  const items = [{ id: 1 }, { id: 2 }]
  const asked = []
  const tried = await loadMissingCovers(items, async id => {
    asked.push(id)
    return id === 1 ? '/minio/cover-1.jpg' : null
  })

  assert.deepEqual(asked.sort(), [1, 2])
  assert.equal(items[0].coverUrl, '/minio/cover-1.jpg')
  assert.equal(items[0].coverFailed, undefined)
  assert.equal(items[1].coverFailed, true)
  assert.equal(tried.length, 2)
  assert.deepEqual(pickCoverCandidates(items), [])
})

test('请求抛异常按失败处理，不影响其它条目', async () => {
  const items = [{ id: 1 }, { id: 2 }, { id: 3 }]
  await loadMissingCovers(items, async id => {
    if (id === 1) throw new Error('boom')
    return `/minio/cover-${id}.jpg`
  })

  assert.equal(items[0].coverFailed, true)
  assert.equal(items[1].coverUrl, '/minio/cover-2.jpg')
  assert.equal(items[2].coverUrl, '/minio/cover-3.jpg')
})

test('并发不超过上限', async () => {
  const items = Array.from({ length: 7 }, (_, index) => ({ id: index + 1 }))
  let inFlight = 0
  let peak = 0

  await loadMissingCovers(items, async id => {
    inFlight += 1
    peak = Math.max(peak, inFlight)
    await new Promise(resolve => setTimeout(resolve, 5))
    inFlight -= 1
    return `/minio/cover-${id}.jpg`
  }, { concurrency: 2 })

  assert.ok(peak <= 2, `峰值并发 ${peak} 超过上限 2`)
  assert.equal(items.filter(item => item.coverUrl).length, 7)
})

test('空列表与非法入参不报错', async () => {
  assert.deepEqual(await loadMissingCovers([], async () => '/minio/x.jpg'), [])
  assert.deepEqual(await loadMissingCovers(null, async () => '/minio/x.jpg'), [])
  assert.deepEqual(pickCoverCandidates(undefined), [])
})
