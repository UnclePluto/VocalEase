import { StrictMode, type ReactNode } from 'react'
import { render } from '@testing-library/react'
import { MemoryRouter, useRoutes } from 'react-router-dom'

import { AppProviders } from '../app/providers'
import { appRoutes } from '../app/router'

function TestRoutes() {
  return useRoutes(appRoutes)
}

export function renderApp(path = '/', options: { strict?: boolean } = {}) {
  const tree: ReactNode = (
    <AppProviders>
      <MemoryRouter initialEntries={[path]}>
        <TestRoutes />
      </MemoryRouter>
    </AppProviders>
  )
  return render(
    options.strict ? <StrictMode>{tree}</StrictMode> : tree,
  )
}
