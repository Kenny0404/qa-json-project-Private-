import { createRouter, createWebHistory } from 'vue-router'
import AdminLayout from '../views/AdminLayout.vue'
import FaqListPage from '../pages/FaqListPage.vue'
import FaqEditPage from '../pages/FaqEditPage.vue'
import ConfigPage from '../pages/ConfigPage.vue'
import LogsPage from '../pages/LogsPage.vue'

export const router = createRouter({
  history: createWebHistory(),
  routes: [
    {
      path: '/',
      redirect: '/admin/faqs',
    },
    {
      path: '/admin',
      component: AdminLayout,
      children: [
        { path: 'faqs', component: FaqListPage },
        { path: 'faqs/new', component: FaqEditPage },
        { path: 'faqs/:id', component: FaqEditPage, props: true },
        { path: 'config', component: ConfigPage },
        { path: 'logs', component: LogsPage },
      ],
    },
  ],
})
