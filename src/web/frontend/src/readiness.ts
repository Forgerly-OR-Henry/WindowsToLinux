export const SKELETON_STATUS = '工程骨架已初始化'

export function readinessMessage(projectName: string): string {
  return `${projectName} ${SKELETON_STATUS}`
}
