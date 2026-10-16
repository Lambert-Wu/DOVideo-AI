<template>
  <!-- 库页用 KeepAlive 缓存：从 Agent 页返回时不重建组件，避免整页重新加载视频资料库。 -->
  <router-view v-slot="{ Component }">
    <keep-alive include="LibraryView">
      <component :is="Component" />
    </keep-alive>
  </router-view>
</template>

<script setup>
import { onMounted, onUnmounted } from 'vue'
import { useRouter } from 'vue-router'
import { useTheme } from './useTheme.js'

const router = useRouter()
const { syncThemeFromDocument } = useTheme()

// 登录态失效时统一退回库页；各视图自己的清理逻辑（清用户、提示）仍由视图负责。
const handleAuthExpired = () => {
  if (router.currentRoute.value.name !== 'library') router.push({ name: 'library' })
}

onMounted(() => {
  syncThemeFromDocument()
  window.addEventListener('auth-expired', handleAuthExpired)
})

onUnmounted(() => {
  window.removeEventListener('auth-expired', handleAuthExpired)
})
</script>
