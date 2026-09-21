<script setup lang="ts">
import { onMounted, ref, watch } from 'vue'
import { request, submitTask, type ServerProfile, type SourceProfile } from '../../shared/api/client'
import { t, errorText } from '../../shared/i18n/messages'
import AppIcon from '../../shared/ui/AppIcon.vue'
const props=defineProps<{active:boolean}>(),emit=defineEmits<{task:[id:string]}>()
const sources=ref<SourceProfile[]>([]),servers=ref<ServerProfile[]>([]),sourceId=ref(''),serverId=ref(''),error=ref(''),busy=ref(false),progress=ref('')
const mode=ref('upload'),name=ref(''),url=ref(''),referenceKind=ref('default'),reference=ref(''),advanced=ref(false)
const values=ref<Record<string,string>>({}),component=ref(''),filesInput=ref<HTMLInputElement>(),folderInput=ref<HTMLInputElement>(),archiveInput=ref<HTMLInputElement>()
const secretEnvironment=ref(''),secretValue=ref('')
const fields=['applicationDeclaration','type','port','version','primary','secondary','healthMode','healthEndpoint','expectedStatus','timeout','stability','accessUrl','configuration','secrets','databaseMode','databaseDetails','containerEngine','ports','volumes','jvmArguments','arguments','jvmTarget','dependencies']
const types=['SPRING_BOOT','JAVA_JAR','JAVA_SOURCE','NODE_SERVICE','PYTHON_SERVICE','STATIC_SITE','DOCKERFILE_CONTAINER','GO_SERVICE','RUST_SERVICE','DOTNET_SERVICE','KOTLIN_SERVICE','PHP_SERVICE','RUBY_SERVICE','CMAKE_SERVICE']
function key(field:string){return component.value?`${component.value}/${field}`:field}
async function load(){try{[sources.value,servers.value]=await Promise.all([request<SourceProfile[]>('sources'),request<ServerProfile[]>('servers')]);error.value=''}catch(e){error.value=errorText(e)}}
async function upload(list:FileList|File[],archive=false){
  const files=Array.from(list);if(!files.length)return
  busy.value=true;error.value=''
  try {
    const first=files[0]!,root=first.webkitRelativePath?.split('/')[0]
    const project=name.value.trim()||root||first.name.replace(/\.(zip|tar\.gz|tgz)$/i,'').replace(/[^A-Za-z0-9_.-]/g,'-')
    const created=await request<SourceProfile>('sources','POST',{name:project});progress.value=`0 / ${files.length}`
    for(let index=0;index<files.length;index++){
      const file=files[index]!,path=file.webkitRelativePath?file.webkitRelativePath.split('/').slice(1).join('/'):file.name
      await request(archive?`sources/${created.id}/archive?format=${file.name.toLowerCase().endsWith('.zip')?'zip':'tar.gz'}`:`sources/${created.id}/files?path=${encodeURIComponent(path)}`,'PUT',file)
      progress.value=`${index+1} / ${files.length}`
    }
    await request(`sources/${created.id}/complete`,'POST');sourceId.value=created.id;await load()
  }catch(e){error.value=errorText(e)}finally{busy.value=false;progress.value='';for(const input of [filesInput.value,folderInput.value,archiveInput.value])if(input)input.value=''}
}
function selected(event:Event,archive=false){const files=(event.target as HTMLInputElement).files;if(files)void upload(files,archive)}
function drop(event:DragEvent){if(event.dataTransfer?.files.length)void upload(event.dataTransfer.files)}
async function git(){busy.value=true;try{emit('task',(await submitTask('GIT_SNAPSHOT',{url:url.value,referenceKind:referenceKind.value,reference:reference.value,...(name.value?{name:name.value}:{})})).id)}catch(e){error.value=errorText(e)}finally{busy.value=false}}
async function run(kind:string){busy.value=true;try{emit('task',(await submitTask(kind,kind==='ANALYZE'?{sourceId:sourceId.value}:{sourceId:sourceId.value,serverId:serverId.value,overrides:values.value})).id)}catch(e){error.value=errorText(e)}finally{busy.value=false}}
async function saveSecret(){busy.value=true;try{const value=secretValue.value;secretValue.value='';const saved=await request<{identifier:string;revision:number}>('secrets','POST',{environment:secretEnvironment.value,value});const previous=values.value[key('secrets')];values.value[key('secrets')]=[previous,`${saved.identifier}:${saved.revision}`].filter(Boolean).join(';');error.value=''}catch(e){error.value=errorText(e)}finally{secretValue.value='';busy.value=false}}
watch(()=>props.active,active=>{if(active)void load()});onMounted(load)
</script>
<template><section>
  <header class="page-heading"><div><p class="eyebrow">DEPLOYMENT</p><h1>{{t('deployTitle')}}</h1><p>{{t('deployHint')}}</p></div><button @click="advanced=!advanced" :aria-expanded="advanced"><AppIcon name="settings"/>{{t('advanced')}}</button></header>
  <p v-if="error" class="error" role="alert">{{error}}</p><div class="deployment-layout" :class="{withAdvanced:advanced}"><div class="deployment-main">
    <section class="panel"><div class="section-heading"><span class="step">1</span><h2>{{t('source')}}</h2></div><div class="segmented"><button :class="{selected:mode==='upload'}" @click="mode='upload'">{{t('localFiles')}}</button><button :class="{selected:mode==='git'}" @click="mode='git'">Git</button></div>
      <label>{{t('projectName')}}<input v-model="name" maxlength="100" placeholder="my-project"/></label>
      <div v-show="mode==='upload'" class="dropzone" @dragover.prevent @drop.prevent="drop"><AppIcon name="folder"/><h3>{{t('dropSource')}}</h3><p>{{t('uploadLimit')}}</p><div class="button-row"><button :disabled="busy" @click="folderInput?.click()">{{t('chooseFolder')}}</button><button :disabled="busy" @click="filesInput?.click()">{{t('chooseFiles')}}</button><button :disabled="busy" @click="archiveInput?.click()">{{t('chooseArchive')}}</button></div>
        <input ref="filesInput" class="visually-hidden" type="file" multiple @change="selected($event)"/><input ref="folderInput" class="visually-hidden" type="file" webkitdirectory multiple @change="selected($event)"/><input ref="archiveInput" class="visually-hidden" type="file" accept=".zip,.tar.gz,.tgz" @change="selected($event,true)"/>
      </div>
      <form v-show="mode==='git'" @submit.prevent="git"><label>{{t('gitUrl')}}<input v-model="url" type="url" required :placeholder="t('gitPlaceholder')"/></label><div class="form-row"><label>{{t('referenceKind')}}<select v-model="referenceKind"><option value="default">{{t('defaultBranch')}}</option><option value="branch">Branch</option><option value="tag">Tag</option><option value="commit">Commit</option></select></label><label v-if="referenceKind!=='default'">{{t('reference')}}<input v-model="reference" required/></label></div><button :disabled="busy" class="primary">{{t('fetchGit')}}</button></form>
      <p class="help">{{t('sourceHint')}}</p><p v-if="busy" role="status">{{t('working')}} {{progress}}</p>
      <label>{{t('project')}}<select v-model="sourceId" :aria-label="t('project')"><option value="">{{t('selectSource')}}</option><option v-for="source in sources.filter(s=>s.state==='READY')" :key="source.id" :value="source.id">{{source.name}} · {{source.kind}}</option></select></label>
    </section>
    <section class="panel"><div class="section-heading"><span class="step">2</span><h2>{{t('targetServer')}}</h2></div><select v-model="serverId" :aria-label="t('targetServer')"><option value="">{{t('selectServer')}}</option><option v-for="server in servers" :key="server.id" :value="server.id">{{server.name}} · {{server.host}}</option></select><p v-if="!servers.length" class="help">{{t('noServersHint')}}</p></section>
    <div class="deploy-actions"><button :disabled="!sourceId||busy" @click="run('ANALYZE')">{{t('analyze')}}</button><button class="primary large" :disabled="!sourceId||!serverId||busy" @click="run('DEPLOY')">{{t('startDeploy')}}<AppIcon name="arrow"/></button></div>
  </div><aside v-show="advanced" class="panel advanced-panel"><h2>{{t('advanced')}}</h2><p class="help">{{t('advancedHint')}}</p><label>{{t('componentScope')}}<input v-model="component" placeholder="main" pattern="[a-z0-9-]*"/></label><label v-for="field in fields" :key="field">{{t('field.'+field)}}
    <select v-if="field==='type'" v-model="values[key(field)]"><option value="">{{t('automatic')}}</option><option v-for="type in types" :key="type">{{type}}</option></select>
    <select v-else-if="['healthMode','containerEngine','databaseMode'].includes(field)" v-model="values[key(field)]"><option value="">{{t('automatic')}}</option><option v-for="value in field==='healthMode'?['HTTP','TCP','PROCESS','COMMAND','UDP']:field==='containerEngine'?['PODMAN','DOCKER']:['NONE','POSTGRESQL','MYSQL','MARIADB','SQLITE','REDIS']" :key="value">{{value}}</option></select>
    <textarea v-else-if="['applicationDeclaration','configuration','databaseDetails','secrets'].includes(field)" v-model="values[key(field)]"  :rows="field==='applicationDeclaration'?10:2" :maxlength="field==='applicationDeclaration'?65536:4096"/><input v-else v-model="values[key(field)]" maxlength="4096"/>
  </label><p class="help">{{t('advancedSyntax')}}</p><form @submit.prevent="saveSecret"><h3>{{t('addSecret')}}</h3><label>{{t('secretEnvironment')}}<input v-model="secretEnvironment" required pattern="[A-Z][A-Z0-9_]{0,39}" maxlength="40" placeholder="API_TOKEN"/></label><label>{{t('secretValue')}}<input v-model="secretValue" type="password" required autocomplete="off" maxlength="65536"/></label><button :disabled="busy">{{t('saveSecret')}}</button><p class="help">{{t('secretTaskHint')}}</p></form></aside></div>
</section></template>
