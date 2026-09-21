import { ref } from 'vue'
import { workbench } from './workbench'
import { execution } from './execution'
export const language = ref<'zh-CN' | 'en'>('zh-CN')
const messages = {
  'runtime.INSTALLED': ['已安装可用', 'Installed'], 'category.APP': ['APP', 'APP'], 'category.UNKNOWN': ['待重新分析', 'Reanalysis required'],
  applicationCommand: ['使用命令', 'Usage command'], copyCommand: ['复制命令', 'Copy command'],
  applicationReanalysis: ['运行声明缺失或版本不支持，请重新分析；原记录和数据已保留。', 'Runtime contract is missing or unsupported. Reanalyze the project; records and data are retained.'],
  deploy: ['部署', 'Deploy'], applications: ['已部署应用', 'Applications'], servers: ['服务器', 'Servers'], ai: ['AI 模型', 'AI models'],
  backup: ['备份与迁移', 'Backup & migration'], tasks: ['任务记录', 'Tasks'], settings: ['设置', 'Settings'],
  internal: ['内部测试，禁止上线', 'Internal testing · Do not publish'], internalDetail: ['此服务只供本机测试使用。登录与多用户权限将在第六期实现。', 'This service is for local testing. Authentication and multi-user permissions arrive in Phase 6.'],
  collapse: ['收起导航', 'Collapse navigation'], expand: ['展开导航', 'Expand navigation'], refresh: ['刷新', 'Refresh'],
  addServer: ['添加服务器', 'Add server'], editServer: ['编辑服务器', 'Edit server'], edit: ['编辑', 'Edit'], remove: ['删除', 'Delete'],
  save: ['保存', 'Save'], cancel: ['取消', 'Cancel'], close: ['关闭', 'Close'], name: ['名称', 'Name'], host: ['主机地址', 'Host'],
  port: ['SSH 端口', 'SSH port'], username: ['用户名', 'Username'], password: ['密码', 'Password'], passwordHint: ['留空保留已保存的密码；修改连接地址时需重新填写。', 'Leave empty to keep the stored password. A changed endpoint needs a new password.'],
  searchServer: ['搜索名称或主机地址', 'Search name or host'], checkConnection: ['检查连接', 'Check connection'],
  connected: ['最近检查通过', 'Last check passed'], disconnected: ['最近检查失败', 'Last check failed'], unchecked: ['尚未检查', 'Not checked'],
  noServers: ['还没有服务器', 'No servers yet'], noServersHint: ['添加 SSH 连接资料，然后在部署页选择目标。', 'Add SSH connection details, then select the target on Deploy.'],
  deployTitle: ['把项目部署到 Linux', 'Deploy your project to Linux'], deployHint: ['选择项目和服务器，剩下的步骤交给任务处理。', 'Choose a project and server. The task handles the remaining steps.'],
  source: ['项目来源', 'Project source'], sourceHint: ['上传的源码只在此处做静态分析，在目标 Linux 上构建。', 'Uploaded source is analyzed here and built on the target Linux host.'],
  chooseFiles: ['选择文件', 'Choose files'], chooseFolder: ['选择文件夹', 'Choose folder'], chooseArchive: ['上传 ZIP / tar.gz', 'Upload ZIP / tar.gz'],
  dropSource: ['拖入项目文件，或选择文件夹', 'Drop project files, or choose a folder'], gitUrl: ['Git 仓库地址', 'Git repository URL'],
  gitPlaceholder: ['https://github.com/owner/project.git', 'https://github.com/owner/project.git'], fetchGit: ['获取源码', 'Fetch source'],
  projectName: ['项目名称（字母、数字或短横线）', 'Project name (letters, numbers or hyphens)'], project: ['项目', 'Project'],
  targetServer: ['目标服务器', 'Target server'], selectServer: ['请选择服务器', 'Choose a server'], selectSource: ['请选择已上传项目', 'Choose an uploaded project'],
  analyze: ['分析项目', 'Analyze project'], startDeploy: ['开始部署', 'Deploy'], advanced: ['高级选项', 'Advanced options'],
  advancedHint: ['保留空白可使用源码中的声明；不明确的参数会在任务中集中询问。', 'Leave blank to use source declarations. Missing parameters will be requested in the task.'],
  runtimePort: ['应用端口', 'Application port'], runtimeVersion: ['运行时版本', 'Runtime version'], artifact: ['构建产物 / 输出目录', 'Artifact / output directory'],
  entrypoint: ['启动入口', 'Entry point'], healthMode: ['健康检查', 'Health check'], accessUrl: ['访问地址', 'Access URL'],
  uploading: ['正在上传…', 'Uploading…'], working: ['正在处理…', 'Working…'], ready: ['可用', 'Ready'],
  taskStarted: ['任务已提交，可在任务记录中继续查看。', 'Task submitted. Follow it in Tasks.'], noTasks: ['暂无任务', 'No tasks yet'],
  taskHint: ['关闭页面不会取消后台任务；重启后未完成的任务需要重新验证。', 'Closing the page does not cancel work. Unfinished tasks require revalidation after a restart.'],
  taskDetails: ['任务详情', 'Task details'], logs: ['执行记录', 'Activity'], waiting: ['需要你的决定', 'Your decision is needed'],
  fingerprint: ['SSH 主机指纹', 'SSH host fingerprint'], fingerprintHint: ['请与服务器管理员提供的指纹核对。确认后才会继续连接。', 'Compare this with the fingerprint from your server administrator before continuing.'],
  accept: ['确认并继续', 'Confirm and continue'], reject: ['拒绝', 'Decline'], submitted: ['已提交', 'Submitted'],
  theme: ['界面主题', 'Appearance'], light: ['浅色', 'Light'], dark: ['深色', 'Dark'], system: ['跟随系统', 'System'],
  language: ['语言', 'Language'], appearanceHint: ['偏好保存在当前工作区和用户下，切换时保留页面输入。', 'Preferences belong to this workspace and user. Switching preserves page input.'],
  connectionError: ['无法连接 Web 服务，请确认后端已启动并从后端地址打开页面。', 'Cannot connect. Start the backend and open its URL.'],
  inputError: ['输入未通过校验，请检查字段、文件格式与大小。', 'Invalid input. Check the fields, file format and size.'],
  conflictError: ['资源已变化或任务队列已满，请刷新后重试。', 'The resource changed or the task queue is full. Refresh and retry.'],
  operationError: ['操作未完成，请检查配置和连接后重试。', 'The operation failed. Check configuration and connectivity.'],
  notFound: ['资源不存在，请刷新列表。', 'Resource not found. Refresh the list.'],
  rejected: ['请求被拒绝，请从本机服务地址打开页面。', 'Request rejected. Open the local service URL.'],
  confirmDelete: ['删除这条服务器资料？已有应用或任务引用时不能删除。', 'Delete this server? References from applications or tasks prevent deletion.'],
  unknown: ['未知', 'Unknown'], allServers: ['全部服务器', 'All servers'], allTypes: ['全部类型', 'All types'],
  state_QUEUED: ['等待执行', 'Queued'], state_RUNNING: ['执行中', 'Running'], state_ANALYZING: ['分析中', 'Analyzing'],
  state_WAITING_DECISION: ['等待决定', 'Awaiting decision'], state_CANCELLING: ['正在取消', 'Cancelling'], state_CANCELLED: ['已取消', 'Cancelled'],
  state_SUCCEEDED: ['已完成', 'Succeeded'], state_FAILED: ['执行失败', 'Failed'], state_INTERRUPTED: ['被系统中断', 'Interrupted'],
  state_REVALIDATION_REQUIRED: ['需要重新验证', 'Revalidation required'],
} as const
export type MessageKey = keyof typeof messages
export function t(key: string, parameters?: Record<string, unknown>) {
  const value=((messages as Record<string, readonly [string, string]>)[key]??workbench[key]??execution[key])?.[language.value === 'en' ? 1 : 0] ?? key
  return value.replace(/\{(\w+)\}/g, (_, name:string) => String(parameters?.[name] ?? '…'))
}
export function errorText(error: unknown) {
  const code = typeof error === 'object' && error !== null && 'code' in error ? String(error.code) : 'OPERATION_FAILED'
  return t(({CONNECTION_FAILED:'connectionError', INVALID_INPUT:'inputError', STATE_CONFLICT:'conflictError', NOT_FOUND:'notFound', REQUEST_REJECTED:'rejected'} as Record<string,string>)[code] ?? 'operationError')
}
