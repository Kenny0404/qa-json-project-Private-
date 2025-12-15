import { http } from './http'

export interface Faq {
  id: number
  category?: string | null
  module?: string | null
  source?: string | null
  question: string
  answer: string
  score?: number | null
}

export interface AdminListResponse<T> {
  success: boolean
  data: T
  count?: number
  message?: string
}

export type RuntimeConfigSnapshot = {
  rag: {
    defaultTopN: number
    retrievalTopK: number
    rrfK: number
  }
  guardrail: {
    escalateAfter: number
    contactName: string
    contactPhone: string
    contactEmail: string
  }
}

export type RuntimeConfigUpdate = Partial<{
  rag: Partial<RuntimeConfigSnapshot['rag']>
  guardrail: Partial<RuntimeConfigSnapshot['guardrail']>
}>

export type AdminLogEntry = {
  timestampMs: number
  level: 'INFO' | 'WARN'
  action: string
  message: string
  data?: Record<string, unknown>
}

export async function listFaq() {
  const res = await http.get('/api/admin/faq')
  return res.data as AdminListResponse<Faq[]>
}

export async function getFaq(id: number) {
  const res = await http.get(`/api/admin/faq/${id}`)
  return res.data as AdminListResponse<Faq | null>
}

export async function createFaq(faq: Partial<Faq>) {
  const res = await http.post('/api/admin/faq', faq)
  return res.data as AdminListResponse<Faq>
}

export async function updateFaq(id: number, faq: Partial<Faq>) {
  const res = await http.put(`/api/admin/faq/${id}`, faq)
  return res.data as AdminListResponse<Faq | null>
}

export async function deleteFaq(id: number) {
  const res = await http.delete(`/api/admin/faq/${id}`)
  return res.data as { success: boolean; deleted: boolean; message?: string }
}

export async function reindex() {
  const res = await http.post('/api/admin/reindex')
  return res.data as { success: boolean; message?: string }
}

export async function getConfig() {
  const res = await http.get('/api/admin/config')
  return res.data as AdminListResponse<RuntimeConfigSnapshot>
}

export async function updateConfig(update: RuntimeConfigUpdate) {
  const res = await http.put('/api/admin/config', update)
  return res.data as AdminListResponse<RuntimeConfigSnapshot>
}

export async function queryLogs(params: {
  sinceMs?: number
  limit?: number
  actionContains?: string
  level?: string
}) {
  const res = await http.get('/api/admin/logs', { params })
  return res.data as AdminListResponse<AdminLogEntry[]>
}
