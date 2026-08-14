import type { FieldErrors } from './types'

export class ApiError extends Error {
  readonly code: string
  readonly requestId: string
  readonly fieldErrors?: FieldErrors
  readonly status?: number

  constructor(
    code: string,
    message: string,
    requestId = '',
    fieldErrors?: FieldErrors,
    status?: number,
  ) {
    super(message)
    this.name = 'ApiError'
    this.code = code
    this.requestId = requestId
    this.fieldErrors = fieldErrors
    this.status = status
  }
}
