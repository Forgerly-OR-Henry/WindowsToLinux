<script setup lang="ts">
import { computed, onMounted, ref, watch } from "vue";
import { request, submitTask, type ServerProfile } from "../../shared/api/client";
import { t, errorText } from "../../shared/i18n/messages";
import AppIcon from "../../shared/ui/AppIcon.vue";
export interface ApplicationProfile {
  id: string;
  name: string;
  serverId: string;
  kind: string;
  version: number;
  category: string;
  accessUrl: string;
  deployedAt: string;
  deploymentState: string;
  types?: string[];
  canLifecycle?: boolean;
  reanalysisRequired?: boolean;
  usage?: {
    category: string;
    mode: string;
    reviewed: boolean;
    lifecycle: boolean;
    command: string;
    endpoints: string[];
  }[];
  observation?: { state?: string; observedAt?: string };
}
const props = defineProps<{ active: boolean }>(),
  emit = defineEmits<{ task: [id: string] }>();
const applications = ref<ApplicationProfile[]>([]),
  servers = ref<ServerProfile[]>([]),
  search = ref(""),
  server = ref(""),
  type = ref(""),
  error = ref(""),
  editor = ref<HTMLDialogElement>(),
  editing = ref<ApplicationProfile>(),
  busy = ref(false);
const form = ref({ name: "", category: "APP", accessUrl: "", version: 0 });
const filtered = computed(() =>
  applications.value.filter(
    (app) =>
      (!server.value || app.serverId === server.value) &&
      (!type.value || app.types?.includes(type.value)) &&
      app.name.toLowerCase().includes(search.value.toLowerCase()),
  ),
);
const types = computed(() => [...new Set(applications.value.flatMap((app) => app.types ?? []))]);
async function load() {
  try {
    [applications.value, servers.value] = await Promise.all([
      request<ApplicationProfile[]>("applications"),
      request<ServerProfile[]>("servers"),
    ]);
    error.value = "";
  } catch (e) {
    error.value = errorText(e);
  }
}
async function action(app: ApplicationProfile, action: string) {
  if (action !== "REFRESH_STATUS" && !window.confirm(t("confirmLifecycle"))) return;
  try {
    emit("task", (await submitTask("LIFECYCLE", { applicationId: app.id, action })).id);
  } catch (e) {
    error.value = errorText(e);
  }
}
async function scan() {
  if (!server.value) return;
  try {
    emit("task", (await submitTask("APPLICATION_SCAN", { serverId: server.value })).id);
  } catch (e) {
    error.value = errorText(e);
  }
}
function edit(app: ApplicationProfile) {
  editing.value = app;
  form.value = { name: app.name, category: app.category, accessUrl: app.accessUrl, version: app.version };
  editor.value?.showModal();
}
async function save() {
  if (!editing.value) return;
  busy.value = true;
  try {
    await request(`applications/${editing.value.id}`, "PUT", form.value);
    editor.value?.close();
    await load();
  } catch (e) {
    error.value = errorText(e);
  } finally {
    busy.value = false;
  }
}
async function copyCommand(command: string) {
  try {
    await navigator.clipboard.writeText(command);
  } catch (e) {
    error.value = errorText(e);
  }
}
function actions(app: ApplicationProfile) {
  return [
    "REFRESH_STATUS",
    ...(app.canLifecycle
      ? ["START", "STOP", "RESTART", ...(app.kind === "MANAGED" ? ["ENABLE_AUTOSTART", "DISABLE_AUTOSTART"] : [])]
      : []),
  ];
}
function safeUrl(value: string) {
  return /^https?:\/\//i.test(value) ? value : undefined;
}
watch(
  () => props.active,
  (active) => {
    if (active) void load();
  },
);
onMounted(load);
</script>
<template>
  <section
    ><header class="page-heading"
      ><div
        ><p class="eyebrow">APPLICATIONS</p><h1>{{ t("applications") }}</h1
        ><p>{{ t("applicationHint") }}</p></div
      ><button @click="load"><AppIcon name="refresh" />{{ t("refresh") }}</button></header
    ><p v-if="error" class="error" role="alert">{{ error }}</p>
    <div class="toolbar"
      ><input v-model="search" :placeholder="t('searchApplication')" :aria-label="t('searchApplication')" /><select
        v-model="server"
        :aria-label="t('targetServer')"
        ><option value="">{{ t("allServers") }}</option
        ><option v-for="item in servers" :key="item.id" :value="item.id">{{ item.name }}</option></select
      ><select v-model="type" :aria-label="t('field.type')"
        ><option value="">{{ t("allTypes") }}</option
        ><option v-for="item in types" :key="item">{{ item }}</option></select
      ><button :disabled="!server" @click="scan">{{ t("scanApplications") }}</button></div
    >
    <div v-if="!filtered.length" class="empty"
      ><AppIcon name="applications" /><h2>{{ t("noApplications") }}</h2
      ><p>{{ t("noApplicationsHint") }}</p></div
    >
    <div class="card-grid"
      ><article v-for="app in filtered" :key="app.id" class="resource-card"
        ><div class="card-heading"
          ><span class="icon-tile"><AppIcon name="applications" /></span
          ><div
            ><h2>{{ app.name }}</h2
            ><p
              >{{ servers.find((s) => s.id === app.serverId)?.name }} · {{ app.types?.join(" / ") || t("external") }}</p
            ></div
          ><button class="icon-button" :aria-label="t('edit')" @click="edit(app)"
            ><AppIcon name="settings" /></button></div
        ><span
          class="status-badge"
          :class="{ success: ['RUNNING', 'INSTALLED'].includes(app.observation?.state ?? '') }"
          >{{ t("runtime." + (app.observation?.state ?? "UNKNOWN")) }}</span
        ><p class="help"
          >{{ t("lastObserved") }}
          {{ app.observation?.observedAt ? new Date(app.observation.observedAt).toLocaleString() : t("unknown") }}</p
        ><p>{{ t("category." + app.category) }} · {{ new Date(app.deployedAt).toLocaleDateString() }}</p
        ><a v-if="safeUrl(app.accessUrl)" :href="safeUrl(app.accessUrl)" target="_blank" rel="noopener noreferrer">{{
          t("openApplication")
        }}</a>
        <p v-if="['REVALIDATION_REQUIRED', 'MANUAL_RECOVERY_REQUIRED'].includes(app.deploymentState)" class="error">{{
          t("applicationRevalidation")
        }}</p>
        <p v-if="app.reanalysisRequired" class="error">{{ t("applicationReanalysis") }}</p>
        <div v-for="(usage, index) in app.usage" :key="index"
          ><p v-for="endpoint in usage.endpoints" :key="endpoint"
            ><a v-if="safeUrl(endpoint)" :href="safeUrl(endpoint)" target="_blank" rel="noopener noreferrer">{{
              endpoint
            }}</a
            ><code v-else>{{ endpoint }}</code></p
          ><details v-if="usage.command && usage.category === 'APP'"
            ><summary>{{ t("applicationCommand") }}</summary
            ><pre>{{ usage.command }}</pre
            ><button @click="copyCommand(usage.command)">{{ t("copyCommand") }}</button></details
          ></div
        >
        <footer class="wrap"
          ><button v-for="actionName in actions(app)" :key="actionName" @click="action(app, actionName)">{{
            t("action." + actionName)
          }}</button></footer
        >
      </article></div
    >
    <dialog ref="editor"
      ><form @submit.prevent="save"
        ><header
          ><h2>{{ t("editApplication") }}</h2
          ><button type="button" class="icon-button" :aria-label="t('close')" @click="editor?.close()"
            ><AppIcon name="close" /></button></header
        ><p v-if="error" class="error" role="alert">{{ error }}</p
        ><label>{{ t("name") }}<input v-model="form.name" required maxlength="200" /></label
        ><label
          >{{ t("category")
          }}<select v-model="form.category" :disabled="editing?.kind === 'MANAGED'"
            ><option v-for="item in ['WEBSITE', 'APP', 'UNKNOWN']" :key="item" :value="item">{{
              t("category." + item)
            }}</option></select
          ></label
        ><label>{{ t("accessUrl") }}<input v-model="form.accessUrl" type="url" /></label
        ><footer
          ><button type="button" @click="editor?.close()">{{ t("cancel") }}</button
          ><button class="primary" :disabled="busy">{{ t("save") }}</button></footer
        ></form
      ></dialog
    >
  </section>
</template>
