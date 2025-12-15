import axios from 'axios'
import type { InternalAxiosRequestConfig } from 'axios'

const baseURL = import.meta.env.VITE_API_BASE_URL || ''
const adminKey = import.meta.env.VITE_ADMIN_KEY || ''

export const http = axios.create({
  baseURL,
})

http.interceptors.request.use((config: InternalAxiosRequestConfig) => {
  if (adminKey && adminKey.trim().length > 0) {
    config.headers = config.headers || {}
    config.headers['X-Admin-Key'] = adminKey
  }
  return config
})
