import { createRouter, createWebHistory } from 'vue-router'
import LibraryView from './views/LibraryView.vue'
import AgentView from './views/AgentView.vue'

export const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/', name: 'library', component: LibraryView },
    // 单独一页的 Video Agent：/agent/<mediaId>，刷新/分享链接都能直达。
    { path: '/agent/:id(\\d+)', name: 'agent', component: AgentView },
    { path: '/:pathMatch(.*)*', redirect: '/' }
  ]
})
