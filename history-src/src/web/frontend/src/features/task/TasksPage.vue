<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from "vue";
import { request, submitTask, terminal, type TaskSnapshot, type TaskEvent } from "../../shared/api/client";
import { t, errorText } from "../../shared/i18n/messages";
import AppIcon from "../../shared/ui/AppIcon.vue";
const props = defineProps<{ active: boolean; focusId: string }>(),
  emit = defineEmits<{ task: [id: string] }>();
type TaskDetails = Record<string, unknown>;
interface InputField {
  id: string;
  labelKey: string;
  helpKey: string;
  value: string;
  choices: string[];
}
interface AnalysisComponent {
  id: string;
  path: string;
  types: string[];
  admission?: string;
  rejections?: string[];
}
interface ScanCandidate {
  name: string;
  managed: boolean;
  state: string;
  target: { key: string };
}
const tasks = ref<TaskSnapshot[]>([]),
  selected = ref(""),
  task = ref<TaskSnapshot>(),
  events = ref<TaskEvent[]>([]),
  error = ref(""),
  busy = ref(false),
  values = ref<Record<string, string>>({}),
  password = ref("");
const confirmations = ref({ backupConfirmed: false, downtimeConfirmed: false, replacementConfirmed: false });
let stream: EventSource | undefined,
  timer: ReturnType<typeof setInterval> | undefined,
  connecting = "",
  refreshing = false,
  historyId = "";
const fields = computed(() => (task.value?.decision?.prompt.fields as InputField[] | undefined) ?? []);
const components = computed(() => (task.value?.result?.components as AnalysisComponent[] | undefined) ?? []);
const candidates = computed(
  () => (task.value?.result?.scan as { applications?: ScanCandidate[] } | undefined)?.applications ?? [],
);
const accessUrl = computed(() => {
  const value = task.value?.result?.accessUrl;
  return typeof value === "string" && /^https?:\/\//i.test(value) ? value : "";
});
function stopStream() {
  stream?.close();
  stream = undefined;
  connecting = "";
}
async function load() {
  if (refreshing) return;
  refreshing = true;
  try {
    tasks.value = await request<TaskSnapshot[]>("tasks");
    if (!selected.value && tasks.value[0]) selected.value = tasks.value[0].id;
    await detail();
    error.value = "";
  } catch (e) {
    error.value = errorText(e);
  } finally {
    refreshing = false;
  }
}
async function detail() {
  const id = selected.value;
  if (!id) return;
  const value = await request<TaskSnapshot>(`tasks/${id}`);
  if (id !== selected.value) return;
  task.value = value;
  if (props.active && connecting !== id && (!terminal(value.state) || historyId !== id)) connect(id);
}
function connect(id: string) {
  stopStream();
  connecting = id;
  historyId = id;
  const after = events.value.at(-1)?.sequence ?? 0;
  stream = new EventSource(`/api/v1/tasks/${id}/events?after=${after}`);
  stream.addEventListener("task", (event) => {
    if (id !== selected.value) return;
    try {
      const value = JSON.parse((event as MessageEvent).data) as TaskEvent;
      if (!events.value.some((item) => item.sequence === value.sequence)) {
        events.value.push(value);
        if (events.value.length > 500) events.value.shift();
      }
      if (value.kind === "STATE" && terminal(value.message)) stopStream();
      void detail().catch((e) => (error.value = errorText(e)));
    } catch {
      error.value = t("operationError");
    }
  });
  stream.onerror = () => {
    stopStream();
  };
}
async function select(id: string) {
  stopStream();
  selected.value = id;
  historyId = "";
  events.value = [];
  try {
    await detail();
  } catch (e) {
    error.value = errorText(e);
  }
}
async function answer(accepted = true) {
  if (!task.value?.decision) return;
  busy.value = true;
  error.value = "";
  const decision = task.value.decision,
    id = task.value.id;
  try {
    let body: unknown = { accepted };
    if (!accepted && decision.kind !== "HOST_KEY" && decision.kind !== "CONFIRM") {
      await request(`tasks/${id}/cancel`, "POST");
      await detail();
      return;
    }
    if (decision.kind === "INPUTS") body = { values: values.value };
    if (decision.kind === "SECRET") {
      const value = password.value;
      password.value = "";
      body = await request<{ secretId: string }>("secrets", "POST", { value });
    }
    if (decision.kind === "DATABASE_REPLACEMENT") body = confirmations.value;
    await request(`tasks/${id}/decisions/${decision.id}`, "POST", body);
    await load();
  } catch (e) {
    error.value = errorText(e);
  } finally {
    password.value = "";
    busy.value = false;
  }
}
async function cancel() {
  if (!task.value || !window.confirm(t("cancelTaskConfirm"))) return;
  try {
    await request(`tasks/${task.value.id}/cancel`, "POST");
    await load();
  } catch (e) {
    error.value = errorText(e);
  }
}
async function adopt(key: string) {
  try {
    emit("task", (await submitTask("APPLICATION_ADOPT", { scanTaskId: selected.value, candidateKey: key })).id);
  } catch (e) {
    error.value = errorText(e);
  }
}
watch(
  () => task.value?.decision?.id,
  () => {
    password.value = "";
    values.value = Object.fromEntries(fields.value.map((field) => [field.id, field.value]));
    confirmations.value = { backupConfirmed: false, downtimeConfirmed: false, replacementConfirmed: false };
  },
);
watch(
  () => props.focusId,
  (id) => {
    if (id) void select(id);
  },
);
watch(
  () => props.active,
  (active) => {
    if (timer) clearInterval(timer);
    if (active) {
      void load();
      timer = setInterval(load, 3000);
    } else stopStream();
  },
  { immediate: true },
);
onBeforeUnmount(() => {
  stopStream();
  if (timer) clearInterval(timer);
});
</script>
<template>
  <section
    ><header class="page-heading"
      ><div
        ><p class="eyebrow">ACTIVITY</p><h1>{{ t("tasks") }}</h1
        ><p>{{ t("taskHint") }}</p></div
      ><button @click="load"><AppIcon name="refresh" />{{ t("refresh") }}</button></header
    ><p v-if="error" class="error" role="alert">{{ error }}</p>
    <div v-if="!tasks.length" class="empty"
      ><AppIcon name="tasks" /><h2>{{ t("noTasks") }}</h2></div
    >
    <div v-else class="task-layout"
      ><aside class="task-list"
        ><button
          v-for="item in tasks"
          :key="item.id"
          :class="{ selected: item.id === selected }"
          @click="select(item.id)"
          ><strong>{{ t("kind." + item.kind) }}</strong
          ><span
            class="status-badge"
            :class="{ success: item.state === 'SUCCEEDED', warning: item.state === 'WAITING_DECISION' }"
            >{{ t("state_" + item.state) }}</span
          ><small>{{ new Date(item.createdAt).toLocaleString() }}</small></button
        ></aside
      >
      <section v-if="task" class="panel task-detail"
        ><header class="section-heading"
          ><div
            ><h2>{{ t("kind." + task.kind) }}</h2
            ><p class="muted">{{ t("state_" + task.state) }} · {{ new Date(task.updatedAt).toLocaleString() }}</p></div
          ><button v-if="!terminal(task.state)" class="danger-text" @click="cancel">{{ t("cancel") }}</button></header
        >
        <p v-if="task.errorCode" class="error" role="status">{{ t("error." + task.errorCode) }}</p>
        <form
          v-if="task.decision && task.state === 'WAITING_DECISION'"
          class="decision-panel"
          @submit.prevent="answer()"
          ><h3>{{ t("waiting") }}</h3
          ><p class="help">{{ t("decisionExpiry") }} {{ new Date(task.decision.expiresAt).toLocaleString() }}</p>
          <template v-if="task.decision.kind === 'HOST_KEY'"
            ><p>{{ t("fingerprintHint") }}</p
            ><dl
              ><dt>{{ t("host") }}</dt
              ><dd>{{ task.decision.prompt.host }}</dd
              ><dt>{{ t("fingerprint") }}</dt
              ><dd class="mono break">{{ task.decision.prompt.fingerprint }}</dd></dl
            ></template
          >
          <template v-else-if="task.decision.kind === 'INPUTS'"
            ><label v-for="field in fields" :key="field.id"
              ><small class="muted">{{ field.id.split("/").slice(0, -1).join(" / ") }}</small
              >{{ t((field.id.startsWith("db/") ? "db.field." : "field.") + field.id.split("/").at(-1))
              }}<select v-if="field.choices.length" v-model="values[field.id]" required
                ><option v-if="!field.choices.includes('')" disabled value="">{{ t("chooseValue") }}</option
                ><option v-for="choice in field.choices" :key="choice" :value="choice">{{
                  choice || t("none")
                }}</option></select
              ><textarea
                v-else-if="field.id.split('/').at(-1) === 'applicationDeclaration'"
                v-model="values[field.id]"
                rows="12"
                maxlength="65536" /><input v-else v-model="values[field.id]" maxlength="4096" /></label
          ></template>
          <template v-else-if="task.decision.kind === 'SECRET'"
            ><label
              >{{ t(String(task.decision.prompt.code))
              }}<input v-model="password" type="password" required maxlength="65536" autocomplete="off" /></label
            ><p class="help">{{ t("secretTaskHint") }}</p></template
          >
          <template v-else-if="task.decision.kind === 'DATABASE_REPLACEMENT'"
            ><p>{{ t("replaceDatabaseWarning") }}</p
            ><dl
              ><template v-for="(value, key) in task.decision.prompt" :key="key"
                ><dt>{{ t("detail." + key) }}</dt
                ><dd>{{ value }}</dd></template
              ></dl
            ><label
              v-for="key in ['backupConfirmed', 'downtimeConfirmed', 'replacementConfirmed'] as const"
              :key="key"
              class="checkbox"
              ><input v-model="confirmations[key]" type="checkbox" required />{{ t(key) }}</label
            ></template
          >
          <template v-else
            ><p>{{ t("confirm." + String(task.decision.prompt.code), task.decision.prompt.details as TaskDetails) }}</p
            ><dl v-if="task.decision.prompt.details"
              ><template v-for="(value, key) in task.decision.prompt.details as TaskDetails" :key="key"
                ><dt>{{ t("detail." + key) }}</dt
                ><dd>{{ Array.isArray(value) ? value.join(", ") : value }}</dd></template
              ></dl
            ></template
          >
          <footer
            ><button type="button" :disabled="busy" @click="answer(false)">{{ t("reject") }}</button
            ><button class="primary" :disabled="busy">{{ t("accept") }}</button></footer
          >
        </form>
        <div v-if="task.result" class="task-result"
          ><p v-if="task.result.status"
            ><strong>{{ t("result." + String(task.result.status)) }}</strong></p
          ><a v-if="accessUrl" :href="accessUrl" target="_blank" rel="noopener noreferrer">{{
            t("openApplication")
          }}</a>
          <article v-for="component in components" :key="component.id" class="result-item"
            ><strong>{{ component.id }}</strong
            ><span>{{ component.types?.join(" · ") }}</span
            ><p>{{ component.path || "." }} · {{ component.admission }}</p
            ><p v-if="component.rejections?.length" class="help">{{ component.rejections.join(" · ") }}</p></article
          >
          <article v-for="candidate in candidates" :key="candidate.target.key" class="result-item"
            ><strong>{{ candidate.name }}</strong
            ><span>{{ t("runtime." + candidate.state) }}</span
            ><button v-if="!candidate.managed" @click="adopt(candidate.target.key)">{{ t("adopt") }}</button
            ><span v-else class="help">{{ t("alreadyManaged") }}</span></article
          >
        </div>
        <h3>{{ t("logs") }}</h3
        ><ol class="event-list"
          ><li v-for="event in events" :key="event.sequence"
            ><time>{{ new Date(event.createdAt).toLocaleTimeString() }}</time
            ><span>{{ t("event." + event.message) }}</span
            ><small v-if="event.details.component">{{ event.details.component }}</small
            ><dl v-if="event.message === 'STORAGE_PREFLIGHT'"
              ><template
                v-for="key in ['application', 'binding', 'kind', 'source', 'access', 'host', 'mode']"
                :key="key"
                ><dt>{{ t("detail.storage." + key) }}</dt
                ><dd class="mono break">{{ event.details[key] }}</dd></template
              ></dl
            ></li
          ></ol
        >
      </section>
    </div>
  </section>
</template>
