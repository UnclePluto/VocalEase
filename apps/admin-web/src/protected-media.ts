import { useEffect, useState } from "react";

import { loadProtectedMedia } from "./admin-api";

export function useProtectedMedia(token: string, path?: string): string | undefined {
  const [source, setSource] = useState<string>();
  useEffect(() => {
    if (!path) {
      setSource(undefined);
      return;
    }
    let active = true;
    let objectUrl: string | undefined;
    void loadProtectedMedia(token, path)
      .then((blob) => {
        if (!active) return;
        objectUrl = URL.createObjectURL(blob);
        setSource(objectUrl);
      })
      .catch(() => {
        if (active) setSource(undefined);
      });
    return () => {
      active = false;
      if (objectUrl) URL.revokeObjectURL(objectUrl);
    };
  }, [path, token]);
  return source;
}
