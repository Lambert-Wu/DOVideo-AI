/**
 * 资料库列表的进程内缓存。
 *
 * 库页拿到列表后写进来；Agent 页进入时先用它取文件名，避免为了一个标题再发一次
 * `/media/list`。拿不到（例如直接刷新 /agent/:id）时调用方再回退到请求。
 */
let items = []

export function setMediaItems(list) {
  items = Array.isArray(list) ? list : []
}

export function findMediaItem(id) {
  return items.find(item => String(item.id) === String(id)) || null
}
