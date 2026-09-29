import { expect, it } from 'vitest'

import { ApiError } from '../../api/errors'
import { describeUploadError } from './manualUpload'

it('向管理员展示 LRC 校验的行号和具体原因', () => {
  const error = new ApiError('validation_error', '请求参数校验失败', '', { lyrics: ['第 3 行时间标签无效'] }, 400)
  expect(describeUploadError(error)).toContain('第 3 行时间标签无效')
})
