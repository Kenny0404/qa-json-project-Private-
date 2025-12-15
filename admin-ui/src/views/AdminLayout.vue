<template>
  <div>
    <div class="nav">
      <div class="container row" style="justify-content: space-between;">
        <div class="row">
          <div style="font-weight: 700; padding: 10px 0;">QA Admin</div>
          <router-link to="/admin/faqs">FAQ</router-link>
          <router-link to="/admin/config">Config</router-link>
          <router-link to="/admin/logs">Logs</router-link>
        </div>
        <div class="muted" style="font-size: 12px;">/api/admin</div>
      </div>
    </div>

    <div class="container" style="padding-top: 18px;">
      <router-view />
    </div>
  </div>
</template>
