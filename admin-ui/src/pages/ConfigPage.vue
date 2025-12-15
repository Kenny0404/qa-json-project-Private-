<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { getConfig, updateConfig, type RuntimeConfigSnapshot } from '../api/admin'

const loading = ref(false)
const error = ref<string | null>(null)
const info = ref<string | null>(null)

const model = ref<RuntimeConfigSnapshot | null>(null)

async function refresh() {
  loading.value = true
  error.value = null
  info.value = null
  try {
    const res = await getConfig()
    model.value = res.data
  } catch (e: any) {
    error.value = e?.message || 'Failed to load config'
  } finally {
    loading.value = false
  }
}

async function save() {
  if (!model.value) return
  loading.value = true
  error.value = null
  info.value = null
  try {
    const res = await updateConfig({
      rag: { ...model.value.rag },
      guardrail: { ...model.value.guardrail },
    })
    model.value = res.data
    info.value = 'Saved (effective immediately)'
  } catch (e: any) {
    error.value = e?.message || 'Failed to save config'
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
        <div style="font-weight: 700; font-size: 18px;">Runtime Config</div>
        <div class="muted" style="font-size: 12px;">Update RAG/Guardrail settings</div>
      </div>
      <div class="row">
        <button class="btn" @click="refresh" :disabled="loading">Refresh</button>
        <button class="btn primary" @click="save" :disabled="loading || !model">Save</button>
      </div>
    </div>

    <div v-if="error" class="error" style="margin-top: 10px;">{{ error }}</div>
    <div v-if="info" class="success" style="margin-top: 10px;">{{ info }}</div>

    <div v-if="model" style="margin-top: 14px; display: grid; grid-template-columns: 1fr 1fr; gap: 16px;">
      <div>
        <div style="font-weight: 600; margin-bottom: 8px;">RAG</div>
        <div class="row" style="align-items: flex-start;">
          <div style="flex: 1;">
            <label class="muted" style="font-size: 12px;">defaultTopN</label>
            <input class="input" type="number" v-model.number="model.rag.defaultTopN" />
          </div>
          <div style="flex: 1;">
            <label class="muted" style="font-size: 12px;">retrievalTopK</label>
            <input class="input" type="number" v-model.number="model.rag.retrievalTopK" />
          </div>
          <div style="flex: 1;">
            <label class="muted" style="font-size: 12px;">rrfK</label>
            <input class="input" type="number" v-model.number="model.rag.rrfK" />
          </div>
        </div>
      </div>

      <div>
        <div style="font-weight: 600; margin-bottom: 8px;">Guardrail</div>
        <div>
          <label class="muted" style="font-size: 12px;">escalateAfter</label>
          <input class="input" type="number" v-model.number="model.guardrail.escalateAfter" />
        </div>
        <div style="margin-top: 10px;">
          <label class="muted" style="font-size: 12px;">contactName</label>
          <input class="input" v-model="model.guardrail.contactName" />
        </div>
        <div style="margin-top: 10px;">
          <label class="muted" style="font-size: 12px;">contactPhone</label>
          <input class="input" v-model="model.guardrail.contactPhone" />
        </div>
        <div style="margin-top: 10px;">
          <label class="muted" style="font-size: 12px;">contactEmail</label>
          <input class="input" v-model="model.guardrail.contactEmail" />
        </div>
      </div>
    </div>
  </div>
</template>
