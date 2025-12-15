<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { createFaq, deleteFaq, getFaq, updateFaq, type Faq } from '../api/admin'
import { useRoute, useRouter } from 'vue-router'

const route = useRoute()
const router = useRouter()

const idParam = computed(() => route.params.id)
const isNew = computed(() => idParam.value === undefined)
const idNumber = computed(() => {
  if (idParam.value === undefined) return null
  const n = Number(idParam.value)
  return Number.isFinite(n) ? n : null
})
const loading = ref(false)
const error = ref<string | null>(null)
const info = ref<string | null>(null)

const model = ref<Partial<Faq>>({
  category: '',
  module: '',
  source: '',
  question: '',
  answer: '',
})

async function load() {
  if (isNew.value) return
  const id = idNumber.value
  if (id == null) return

  loading.value = true
  error.value = null
  try {
    const res = await getFaq(id)
    if (!res.success || !res.data) {
      error.value = 'FAQ not found'
      return
    }
    model.value = { ...res.data }
  } catch (e: any) {
    error.value = e?.message || 'Failed to load'
  } finally {
    loading.value = false
  }
}

async function save() {
  info.value = null
  error.value = null

  if (!model.value.question || !model.value.answer) {
    error.value = 'Question/Answer is required'
    return
  }

  loading.value = true
  try {
    if (isNew.value) {
      const res = await createFaq(model.value)
      info.value = `Created #${res.data.id}`
      router.replace(`/admin/faqs/${res.data.id}`)
    } else {
      const id = idNumber.value
      if (id == null) {
        error.value = 'Invalid id'
        return
      }
      const res = await updateFaq(id, model.value)
      if (!res.success || !res.data) {
        error.value = 'FAQ not found'
      } else {
        info.value = `Saved #${res.data.id}`
      }
    }
  } catch (e: any) {
    error.value = e?.message || 'Failed to save'
  } finally {
    loading.value = false
  }
}

async function remove() {
  if (isNew.value) return
  const id = idNumber.value
  if (id == null) return
  if (!confirm(`Delete FAQ #${id}?`)) return

  loading.value = true
  error.value = null
  info.value = null
  try {
    const res = await deleteFaq(id)
    if (!res.success) {
      error.value = res.message || 'Delete failed'
      return
    }
    router.push('/admin/faqs')
  } catch (e: any) {
    error.value = e?.message || 'Delete failed'
  } finally {
    loading.value = false
  }
}

watch(
  () => route.params.id,
  async () => {
    info.value = null
    await load()
  },
  { immediate: true },
)
</script>

<template>
  <div class="card">
    <div class="row" style="justify-content: space-between;">
      <div>
        <div style="font-weight: 700; font-size: 18px;">{{ isNew ? 'New FAQ' : `Edit FAQ #${route.params.id}` }}</div>
        <div class="muted" style="font-size: 12px;">Changes will trigger reindex in backend</div>
      </div>
      <div class="row">
        <button class="btn" @click="router.push('/admin/faqs')">Back</button>
        <button class="btn primary" @click="save" :disabled="loading">Save</button>
        <button v-if="!isNew" class="btn danger" @click="remove" :disabled="loading">Delete</button>
      </div>
    </div>

    <div v-if="error" class="error" style="margin-top: 10px;">{{ error }}</div>
    <div v-if="info" class="success" style="margin-top: 10px;">{{ info }}</div>

    <div style="margin-top: 14px; display: grid; grid-template-columns: 1fr 1fr; gap: 12px;">
      <div>
        <label class="muted" style="font-size: 12px;">Category</label>
        <input class="input" v-model="model.category" />
      </div>
      <div>
        <label class="muted" style="font-size: 12px;">Module</label>
        <input class="input" v-model="model.module" />
      </div>
      <div>
        <label class="muted" style="font-size: 12px;">Source</label>
        <input class="input" v-model="model.source" />
      </div>
      <div>
        <label class="muted" style="font-size: 12px;">ID (auto)</label>
        <input class="input" :value="model.id ?? ''" disabled />
      </div>
    </div>

    <div style="margin-top: 12px;">
      <label class="muted" style="font-size: 12px;">Question *</label>
      <input class="input" v-model="model.question" />
    </div>

    <div style="margin-top: 12px;">
      <label class="muted" style="font-size: 12px;">Answer *</label>
      <textarea v-model="model.answer"></textarea>
    </div>
  </div>
</template>
