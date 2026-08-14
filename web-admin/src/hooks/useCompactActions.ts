import { useEffect, useState } from 'react'

const MOBILE_ACTIONS_MAX_WIDTH = 520

function isCompactViewport() {
  return window.innerWidth <= MOBILE_ACTIONS_MAX_WIDTH
}

export function useCompactActions() {
  const [compact, setCompact] = useState(isCompactViewport)

  useEffect(() => {
    const update = () => setCompact(isCompactViewport())
    window.addEventListener('resize', update)
    return () => window.removeEventListener('resize', update)
  }, [])

  return compact
}
