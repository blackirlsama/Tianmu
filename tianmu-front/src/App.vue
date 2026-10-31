<template>

  <router-view/>
</template>

<script setup>
import { onMounted, onBeforeUnmount, watch } from 'vue'
import { useUserStore } from '@/store/user'
import { fetchOnlineReward } from '@/api/user'
import { createOnlineRewardMonitor } from '@/lib/online-reward.mjs'

const users = useUserStore()
const monitor = createOnlineRewardMonitor({
  session: () => users.isLogin ? `${users.user.userId}:${users.user.token}` : '',
  visible: () => document.visibilityState === 'visible',
  request: async (signal) => {
    const before = users.user.coinCount
    const response = await fetchOnlineReward(signal)
    return { ...response.data, before }
  },
  apply: (result) => {
    // A delayed heartbeat must not overwrite a coin spent while it was in flight.
    if (users.user.coinCount === result.before && Number.isInteger(result.coinCount)) {
      users.syncCoinCount(result.coinCount)
    }
  },
})
watch([() => users.user.userId, () => users.user.token], () => monitor.refresh())
onMounted(() => {
  document.addEventListener('visibilitychange', monitor.refresh)
  window.addEventListener('focus', monitor.refresh)
  window.addEventListener('online', monitor.refresh)
  monitor.start()
})
onBeforeUnmount(() => {
  monitor.stop()
  document.removeEventListener('visibilitychange', monitor.refresh)
  window.removeEventListener('focus', monitor.refresh)
  window.removeEventListener('online', monitor.refresh)
})
</script>

<style lang="scss">
#app {
    margin: 0 auto;
    max-width: 2560px;
    background-color: var(--bg1);
}
</style>
