<script setup lang="ts">
import { computed, onMounted, ref, watch } from "vue";
import { request, submitTask, type ServerProfile } from "../../shared/api/client";
import { t, errorText } from "../../shared/i18n/messages";
import AppIcon from "../../shared/ui/AppIcon.vue";
const emit = defineEmits<{ task: [id: string] }>();
const props = defineProps<{ active: boolean }>();
const servers = ref<ServerProfile[]>([]),
  search = ref(""),
  error = ref(""),
  busy = ref(false);
const editor = ref<HTMLDialogElement>(),
  editing = ref<string | null>(null);
const form = ref({ name: "", host: "", port: 22, username: "root", password: "", version: 0 });
const filtered = computed(() =>
  servers.value.filter((s) => `${s.name} ${s.host}`.toLowerCase().includes(search.value.toLowerCase())),
);
async function load() {
  try {
    servers.value = await request<ServerProfile[]>("servers");
    error.value = "";
  } catch (e) {
    error.value = errorText(e);
  }
}
function edit(server?: ServerProfile) {
  editing.value = server?.id ?? null;
  form.value = {
    name: server?.name ?? "",
    host: server?.host ?? "",
    port: server?.port ?? 22,
    username: server?.username ?? "root",
    password: "",
    version: server?.version ?? 0,
  };
  error.value = "";
  editor.value?.showModal();
}
async function save() {
  busy.value = true;
  try {
    await request(editing.value ? `servers/${editing.value}` : "servers", editing.value ? "PUT" : "POST", form.value);
    form.value.password = "";
    editor.value?.close();
    await load();
  } catch (e) {
    error.value = errorText(e);
  } finally {
    busy.value = false;
  }
}
async function probe(server: ServerProfile) {
  try {
    emit("task", (await submitTask("SERVER_PROBE", { serverId: server.id })).id);
  } catch (e) {
    error.value = errorText(e);
  }
}
async function remove(server: ServerProfile) {
  if (!window.confirm(t("confirmDelete"))) return;
  try {
    await request(`servers/${server.id}`, "DELETE", { version: server.version });
    await load();
  } catch (e) {
    error.value = errorText(e);
  }
}
onMounted(load);
watch(
  () => props.active,
  (active) => {
    if (active) void load();
  },
);
</script>
<template>
  <section>
    <header class="page-heading"
      ><div
        ><p class="eyebrow">WORKSPACE</p><h1>{{ t("servers") }}</h1></div
      ><button class="primary" @click="edit()"><AppIcon name="plus" />{{ t("addServer") }}</button></header
    >
    <p v-if="error && !editor?.open" class="error" role="alert">{{ error }}</p>
    <div class="toolbar"
      ><input v-model="search" :placeholder="t('searchServer')" :aria-label="t('searchServer')" /><button @click="load"
        ><AppIcon name="refresh" />{{ t("refresh") }}</button
      ></div
    >
    <div v-if="servers.length === 0 && !error" class="empty"
      ><AppIcon name="servers" /><h2>{{ t("noServers") }}</h2
      ><p>{{ t("noServersHint") }}</p></div
    >
    <div class="card-grid"
      ><article v-for="server in filtered" :key="server.id" class="resource-card">
        <div class="card-heading"
          ><span class="icon-tile"><AppIcon name="servers" /></span
          ><div
            ><h2>{{ server.name }}</h2
            ><p class="mono">{{ server.username }}@{{ server.host }}:{{ server.port }}</p></div
          ></div
        >
        <span class="status-badge" :class="{ success: server.observation.connected }">{{
          t(
            server.observation.connected === true
              ? "connected"
              : server.observation.connected === false
                ? "disconnected"
                : "unchecked",
          )
        }}</span>
        <p
          >{{ server.observation.operatingSystem ?? t("unknown") }}
          <span v-if="server.observation.observedAt" class="muted"
            >· {{ new Date(server.observation.observedAt).toLocaleString() }}</span
          ></p
        >
        <footer
          ><button @click="probe(server)">{{ t("checkConnection") }}</button
          ><button @click="edit(server)">{{ t("edit") }}</button
          ><button class="danger-text" @click="remove(server)">{{ t("remove") }}</button></footer
        >
      </article></div
    >
    <dialog ref="editor" @cancel="form.password = ''" @close="form.password = ''"
      ><form @submit.prevent="save">
        <header
          ><h2>{{ t(editing ? "editServer" : "addServer") }}</h2
          ><button type="button" class="icon-button" :aria-label="t('close')" @click="editor?.close()"
            ><AppIcon name="close" /></button
        ></header>
        <p v-if="error" class="error" role="alert">{{ error }}</p>
        <label>{{ t("name") }}<input v-model="form.name" required maxlength="200" autofocus /></label>
        <label>{{ t("host") }}<input v-model="form.host" required maxlength="253" placeholder="192.0.2.10" /></label>
        <div class="form-row"
          ><label>{{ t("port") }}<input v-model.number="form.port" type="number" min="1" max="65535" required /></label
          ><label
            >{{ t("username")
            }}<input v-model="form.username" required maxlength="128" autocomplete="username" /></label
        ></div>
        <label
          >{{ t("password")
          }}<input v-model="form.password" type="password" :required="!editing" autocomplete="new-password" /></label
        ><p class="help">{{ t("passwordHint") }}</p>
        <footer
          ><button type="button" @click="editor?.close()">{{ t("cancel") }}</button
          ><button class="primary" :disabled="busy">{{ t(busy ? "working" : "save") }}</button></footer
        >
      </form></dialog
    >
  </section>
</template>
