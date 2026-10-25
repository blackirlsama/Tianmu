import { createApp } from 'vue'
import App from './App.vue'
import router from './router'
import { createPinia } from 'pinia'
import { useUserStore } from './store/user'
import { TIANMU_USER } from "@/lib/config"

import ElementPlus from 'element-plus'
import 'element-plus/theme-chalk/index.css'
import zhCn from 'element-plus/es/locale/lang/zh-cn'
import * as ElementPlusIconsVue from '@element-plus/icons-vue'


// 全局样式表
import '@/assets/css/base.css'

const app = createApp(App)

// 添加全局变量
// app.config.globalProperties.$message = ElMessage

// 注册全部element图标
app.use(ElementPlus, { locale: zhCn })
for (const [key, component] of Object.entries(ElementPlusIconsVue)) {
  app.component(key, component)
}
const pinia = createPinia()

app.use(pinia)
app.use(router)
app.mount('#app')

// 当在一个tab标签页面修改user时，监听其他标签同步修改user
// 注意：
// 如果你在当前标签页执行了 localStorage.setItem()，当前标签页不会触发 storage 事件。
// 只有其他标签页（同源的）会收到这个 storage 事件。
window.addEventListener('storage', (event) => {
  if (event.key === TIANMU_USER) {
    const newUser = JSON.parse(event.newValue || '{}');
    const userStore = useUserStore(); // 替换成你的 store 实例
    userStore.user = newUser;
  }
});
