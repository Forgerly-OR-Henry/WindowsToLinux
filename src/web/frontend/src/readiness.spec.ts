import { describe, expect, it } from 'vitest'
import { readinessMessage, SKELETON_STATUS } from './readiness'

describe('readinessMessage', () => {
  it('describes the initialized skeleton', () => {
    expect(readinessMessage('WindowsToLinux')).toBe(
      `WindowsToLinux ${SKELETON_STATUS}`,
    )
  })
})
