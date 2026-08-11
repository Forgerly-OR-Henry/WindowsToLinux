<script setup lang="ts">
import { readinessMessage } from './readiness'
</script>

<template>
  <main class="shell">
    <section class="card" aria-labelledby="page-title">
      <p class="eyebrow">WindowsToLinux</p>
      <h1 id="page-title">Web 前端工程骨架</h1>
      <p class="status">{{ readinessMessage('WindowsToLinux') }}</p>
      <p class="note">
        当前只包含可构建、可测试的界面入口，尚未连接部署业务接口。
      </p>
    </section>
  </main>
</template>
