<script setup lang="ts">
import { onMounted, ref, watch } from "vue";
import { request } from "./shared/api/client";
import { language, t, errorText } from "./shared/i18n/messages";
import AppIcon from "./shared/ui/AppIcon.vue";
import ServerPage from "./features/server/ServerPage.vue";
import DeploymentPage from "./features/deployment/DeploymentPage.vue";
import ApplicationsPage from "./features/application/ApplicationsPage.vue";
import AiPage from "./features/ai/AiPage.vue";
import BackupPage from "./features/backup/BackupPage.vue";
import TasksPage from "./features/task/TasksPage.vue";
const pages = ["deploy", "applications", "servers", "ai", "backup", "tasks"];
const page = ref("deploy"),
  collapsed = ref(false),
  theme = ref("system"),
  focusTask = ref(""),
  error = ref(""),
  loaded = ref(false);
function task(id: string) {
  focusTask.value = id;
  page.value = "tasks";
}
function applyTheme() {
  document.documentElement.dataset.theme = theme.value;
  document.documentElement.lang = language.value;
}
async function save() {
  applyTheme();
  if (!loaded.value) return;
  try {
    await request("preferences", "PUT", {
      theme: theme.value,
      language: language.value,
      navigationCollapsed: String(collapsed.value),
    });
    error.value = "";
  } catch (e) {
    error.value = errorText(e);
  }
}
watch([theme, language, collapsed], save);
onMounted(async () => {
  try {
    const value = await request<Record<string, string>>("preferences");
    theme.value = value.theme ?? "system";
    language.value = value.language === "en" ? "en" : "zh-CN";
    collapsed.value = value.navigationCollapsed === "true";
    applyTheme();
  } catch (e) {
    error.value = errorText(e);
  } finally {
    await Promise.resolve();
    loaded.value = true;
  }
});
</script>
<template>
  <div class="app-shell" :class="{ collapsed }">
    <aside class="navigation"
      ><div class="brand"
        ><span class="brand-mark">W<span>↗</span></span
        ><div v-if="!collapsed"><strong>WindowsToLinux</strong><small>Web</small></div></div
      >
      <nav :aria-label="t('navigation')"
        ><button
          v-for="item in pages"
          :key="item"
          :title="t(item)"
          :aria-label="t(item)"
          :aria-current="page === item ? 'page' : undefined"
          :class="{ active: page === item }"
          @click="page = item"
          ><AppIcon :name="item" /><span v-if="!collapsed">{{ t(item) }}</span></button
        ></nav
      >
      <div class="navigation-bottom"
        ><button
          :class="{ active: page === 'settings' }"
          :aria-label="t('settings')"
          :title="t('settings')"
          @click="page = 'settings'"
          ><AppIcon name="settings" /><span v-if="!collapsed">{{ t("settings") }}</span></button
        ><button
          :aria-label="t(collapsed ? 'expand' : 'collapse')"
          :title="t(collapsed ? 'expand' : 'collapse')"
          @click="collapsed = !collapsed"
          ><AppIcon name="menu" /><span v-if="!collapsed">{{ t("collapse") }}</span></button
        ></div
      >
    </aside>
    <div class="main-column"
      ><div class="internal-banner"
        ><span class="warning-dot"></span>{{ t("internal")
        }}<span class="banner-detail">{{ t("internalDetail") }}</span></div
      >
      <p v-if="error" class="error global-error" role="alert">{{ error }}</p>
      <main>
        <DeploymentPage v-show="page === 'deploy'" :active="page === 'deploy'" @task="task" />
        <ApplicationsPage v-show="page === 'applications'" :active="page === 'applications'" @task="task" />
        <ServerPage v-show="page === 'servers'" :active="page === 'servers'" @task="task" />
        <AiPage v-show="page === 'ai'" :active="page === 'ai'" @task="task" />
        <BackupPage v-show="page === 'backup'" :active="page === 'backup'" @task="task" />
        <TasksPage v-show="page === 'tasks'" :active="page === 'tasks'" :focus-id="focusTask" @task="task" />
        <section v-show="page === 'settings'" class="settings-page"
          ><header class="page-heading"
            ><div
              ><p class="eyebrow">WORKSPACE</p><h1>{{ t("settings") }}</h1
              ><p>{{ t("appearanceHint") }}</p></div
            ></header
          >
          <div class="panel"
            ><h2>{{ t("theme") }}</h2
            ><div class="segmented"
              ><button
                v-for="value in ['light', 'dark', 'system']"
                :key="value"
                :class="{ selected: theme === value }"
                :aria-pressed="theme === value"
                @click="theme = value"
                >{{ t(value) }}</button
              ></div
            ><h2>{{ t("language") }}</h2
            ><select v-model="language" :aria-label="t('language')"
              ><option value="zh-CN">简体中文</option
              ><option value="en">English</option></select
            ></div
          >
        </section> </main
      ><footer class="app-footer">WindowsToLinux <span>·</span> {{ t("localWorkspace") }}</footer>
    </div>
  </div>
</template>
