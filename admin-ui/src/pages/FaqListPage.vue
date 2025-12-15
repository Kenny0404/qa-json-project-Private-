<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { deleteFaq, listFaq, reindex, type Faq } from '../api/admin'
import { useRouter } from 'vue-router'

const router = useRouter()

const loading = ref(false)
const error = ref<string | null>(null)
const items = ref<Faq[]>([])
const keyword = ref('')

const filtered = computed(() => {
  const k = keyword.value.trim().toLowerCase()
  if (!k) return items.value
  return items.value.filter((f) => {
    const hay = `${f.id} ${f.category ?? ''} ${f.module ?? ''} ${f.source ?? ''} ${f.question ?? ''} ${f.answer ?? ''}`
    return hay.toLowerCase().includes(k)
  })
})

async function refresh() {
  loading.value = true
  error.value = null
  try {
    const res = await listFaq()
    items.value = res.data
  } catch (e: any) {
    error.value = e?.message || 'Failed to load'
  } finally {
    loading.value = false
  }
}

async function doReindex() {
  loading.value = true
  error.value = null
  try {
    await reindex()
    await refresh()
  } catch (e: any) {
    error.value = e?.message || 'Failed to reindex'
  } finally {
    loading.value = false
  }
}

async function remove(id: number) {
  if (!confirm(`Delete FAQ #${id}?`)) return
  loading.value = true
  error.value = null
  try {
    const res = await deleteFaq(id)
    if (!res.success) {
      error.value = res.message || 'Delete failed'
      return
    }
    await refresh()
  } catch (e: any) {
    error.value = e?.message || 'Delete failed'
  } finally {
    loading.value = false
  }
}

onMounted(refresh)
</script>

<template>
  <div class="card">
    <div class="row" style="justify-content: space-between;">
      <div>
        <div style="font-weight: 700; font-size: 18px;">FAQ</div>
        <div class="muted" style="font-size: 12px;">CRUD + reindex</div>
      </div>
      <div class="row">
        <button class="btn" @click="refresh" :disabled="loading">Refresh</button>
        <button class="btn" @click="doReindex" :disabled="loading">Reindex</button>
        <button class="btn primary" @click="router.push('/admin/faqs/new')">New</button>
      </div>
    </div>

    <div style="margin-top: 14px;" class="row">
      <input class="input" placeholder="Filter..." v-model="keyword" style="max-width: 360px;" />
      <div class="muted" style="font-size: 12px;">Count: {{ filtered.length }}</div>
    </div>

    <div v-if="error" class="error" style="margin-top: 10px;">{{ error }}</div>

    <div style="margin-top: 12px; overflow:auto;">
      <table class="table">
        <thead>
          <tr>
            <th style="width: 70px;">ID</th>
            <th style="width: 120px;">Category</th>
            <th style="width: 120px;">Module</th>
            <th style="width: 140px;">Source</th>
            <th>Question</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="f in filtered" :key="f.id">
            <td>{{ f.id }}</td>
            <td>{{ f.category }}</td>
            <td>{{ f.module }}</td>
            <td>{{ f.source }}</td>
            <td>
              <div style="font-weight: 600;">{{ f.question }}</div>
              <div class="muted" style="font-size: 12px; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; max-width: 680px;">{{ f.answer }}</div>
              <div style="margin-top: 8px;">
                <button class="btn" @click="router.push(`/admin/faqs/${f.id}`)">Edit</button>
                <button class="btn danger" @click="remove(f.id)" :disabled="loading">Delete</button>
              </div>
            </td>
          </tr>
        </tbody>
      </table>
    </div>
  </div>
</template>
