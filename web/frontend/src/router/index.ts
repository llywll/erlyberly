import { createRouter, createWebHistory } from 'vue-router'
import ConnectView from '../views/ConnectView.vue'
import MainView from '../views/MainView.vue'

const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/', name: 'connect', component: ConnectView },
    { path: '/main', name: 'main', component: MainView }
  ]
})

export default router
