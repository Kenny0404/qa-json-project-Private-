<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { queryLogs, type AdminLogEntry } from '../api/admin'

const loading = ref(false)
const error = ref<string | null>(null)
const items = ref<AdminLogEntry[]>([])

const sinceMinutes = ref<number>(60)
const level = ref<string>('')
const actionContains = ref<string>('')
const limit = ref<number>(200)

async function refresh() {
  loading.value = true
  error.value = null
  try {
    const sinceMs = Date.now() - sinceMinutes.value * 60_000
    const res = await queryLogs({
      sinceMs,
      limit: limit.value,
      level: level.value || undefined,
      actionContains: actionContains.value || undefined,
    })
    items.value = res.data
  } catch (e: any) {
    error.value = e?.message || 'Failed to load logs'
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
        <div style="font-weight: 700; font-size: 18px;">Admin Logs</div>
        <div class="muted" style="font-size: 12px;">In-memory ring buffer</div>
      </div>
      <div class="row">
        <button class="btn" @click="refresh" :disabled="loading">Refresh</button>
      </div>
    </div>

    <div style="margin-top: 14px; display: grid; grid-template-columns: 1fr 1fr 1fr 1fr; gap: 12px;">
      <div>
        <label class="muted" style="font-size: 12px;">Since (minutes)</label>
        <input class="input" type="number" v-model.number="sinceMinutes" />
      </div>
      <div>
        <label class="muted" style="font-size: 12px;">Level</label>
        <select class="input" v-model="level">
          <option value="">(any)</option>
          <option value="INFO">INFO</option>
          <option value="WARN">WARN</option>
        </select>
      </div>
      <div>
        <label class="muted" style="font-size: 12px;">Action contains</label>
        <input class="input" v-model="actionContains" placeholder="faq." />
      </div>
      <div>
        <label class="muted" style="font-size: 12px;">Limit</label>
        <input class="input" type="number" v-model.number="limit" />
      </div>
    </div>

    <div v-if="error" class="error" style="margin-top: 10px;">{{ error }}</div>

    <div style="margin-top: 12px; overflow:auto;">
      <table class="table">
        <thead>
          <tr>
            <th style="width: 170px;">Time</th>
            <th style="width: 80px;">Level</th>
            <th style="width: 160px;">Action</th>
            <th>Message</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="(e, idx) in items" :key="idx">
            <td class="muted">{{ new Date(e.timestampMs).toLocaleString() }}</td>
            <td><span class="badge">{{ e.level }}</span></td>
            <td>{{ e.action }}</td>
            <td>
              <div style="font-weight: 600;">{{ e.message }}</div>
              <pre v-if="e.data" class="muted" style="margin: 6px 0 0; font-size: 12px; white-space: pre-wrap;">{{ JSON.stringify(e.data, null, 2) }}</pre>
            </td>
          </tr>
        </tbody>
      </table>
    </div>
  </div>
</template>
