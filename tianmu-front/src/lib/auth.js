import { TIANMU_USER } from '@/lib/config'

export function readStoredUser(raw = localStorage.getItem(TIANMU_USER)) {
  try {
    const user = JSON.parse(raw || '{}')
    return user && typeof user === 'object' && !Array.isArray(user) ? user : {}
  } catch {
    return {}
  }
}

export function hasAuthToken(user = readStoredUser()) {
  return typeof user?.token === 'string' && user.token.trim().length > 0
}
