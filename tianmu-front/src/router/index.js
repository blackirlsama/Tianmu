import { createRouter, createWebHistory } from 'vue-router'
import { ElMessage } from 'element-plus'
import { TIANMU_USER, TIANMU_LOGIN } from "@/lib/config"
import { agentUser } from '@/api/agent'

const routes = [
  { path: '/agent', name: 'AgentWorkspace', component: () => import('@/views/agent/AgentWorkspace.vue'), meta: { requestAuth: true } },
  { path: '/', redirect: '' },
  { path: '', name: 'Index', component: () => import('@/views/IndexVue.vue'), meta: { requestAuth: false } },
  { path: '/category/:categoryId', name: 'Category', component: () => import('@/views/category/CategoryVideo.vue'), meta: { requestAuth: false } },
  // 创作中心、投稿路由
  {
    path: '/platform',
    redirect: '/platform/home',
    component: () => import('@/views/platform/PlatformView.vue'),
    children: [
      { path: '/platform/home', component: () => import('@/views/platform/children/PlatformHome.vue'), meta: { requestAuth: true } },
      {
        path: '/platform/upload',
        component: () => import('@/views/platform/children/PlatformUpload.vue'),
        redirect: '/platform/upload/video',
        children: [
          { path: '/platform/upload/video', component: () => import('@/views/platform/children/uploadChildren/VideoUpload.vue'), meta: { requestAuth: true } },
        ]
      },
    ]
  },
  // 视频详情路由
  { path: '/video/:videoId', component: () => import('@/views/detail/VideoDetail.vue'), meta: { requestAuth: false } },
  {
    path: '/user', name: 'User', component: () => import('@/views/user/UserView.vue'), meta: {
      requestAuth: true,
    },
    children: [
      { path: '/user/:userId', component: () => import('@/views/user/children/UserHome.vue'), meta: { requestAuth: false } },
      { path: '/user/:userId/upload', component: () => import('@/views/user/children/UserUpload.vue'), meta: { requestAuth: false } },
      { path: '/user/:userId/favlist', component: () => import('@/views/user/children/UserFavlist.vue'), meta: { requestAuth: false } },
      { path: '/user/:userId/relation/follow', component: () => import('@/views/user/children/UserFollow.vue'), meta: { requestAuth: false } },
      { path: '/user/:userId/relation/fans', component: () => import('@/views/user/children/UserFans.vue'), meta: { requestAuth: false } },
    ]
  },
  {
    path: '/search',
    component: () => import('@/views/search/SearchIndex.vue'),
    meta: { requestAuth: false },
    props: route => ({ keyword: route.query.keyword }),
    children: [
      { path: '/search/video', component: () => import('@/views/search//children/SearchVideo.vue'), meta: { requestAuth: false }, props: route => ({ keyword: route.query.keyword }) },
      { path: '/search/user', component: () => import('@/views/search//children/SearchUser.vue'), meta: { requestAuth: false }, props: route => ({ keyword: route.query.keyword }) },
    ]
  },
]

const router = createRouter({
  history: createWebHistory(process.env.BASE_URL),
  routes
})
// 本地没有用户信息就不跳转
router.beforeEach((to, from, next) => {
  if (to.name === 'AgentWorkspace' && !agentUser().token) {
    ElMessage.warning('请先登录后使用 AI 分析');
    return next('/');
  }
  if (to.meta.requestAuth && !localStorage.getItem(TIANMU_USER) && !localStorage.getItem(TIANMU_LOGIN)) {
    ElMessage.warning("请先登录");
    next("/");
  } else {
    next();
  }
});
router.afterEach((to) => {
  document.documentElement.classList.toggle('agent-workspace-page', to.name === 'AgentWorkspace')
})
export default router
