import { useEffect, useMemo, useRef, useState } from "react";

import type { LyricLine } from "./admin-api";

const TIMESTAMP = /^\[(\d{1,3}):(\d{2})(?:[.:](\d{1,3}))?]\s*(.*)$/;

export function parseLrc(lrc: string): LyricLine[] {
  return lrc
    .split(/\r?\n/)
    .flatMap((rawLine) => {
      const match = TIMESTAMP.exec(rawLine.trim());
      if (!match) return [];
      const fraction = (match[3] ?? "").padEnd(3, "0").slice(0, 3);
      return [
        {
          time_ms:
            (Number(match[1]) * 60 + Number(match[2])) * 1000 +
            Number(fraction || "0"),
          text: match[4].trim()
        }
      ];
    })
    .sort((left, right) => left.time_ms - right.time_ms);
}

export function activeLyricIndex(lines: LyricLine[], currentTimeMs: number): number {
  let active = -1;
  for (let index = 0; index < lines.length; index += 1) {
    if (lines[index].time_ms > currentTimeMs) break;
    active = index;
  }
  return active;
}

export function LrcPreview({ audioSrc, lrc }: { audioSrc?: string; lrc: string }) {
  const lines = useMemo(() => parseLrc(lrc), [lrc]);
  const [currentTimeMs, setCurrentTimeMs] = useState(0);
  const activeIndex = activeLyricIndex(lines, currentTimeMs);
  const activeLine = useRef<HTMLParagraphElement>(null);

  useEffect(() => {
    activeLine.current?.scrollIntoView?.({ behavior: "smooth", block: "center" });
  }, [activeIndex]);

  return (
    <section className="lyric-preview" aria-label="歌词同步预览">
      {audioSrc ? (
        <audio
          aria-label="伴奏预览"
          controls
          src={audioSrc}
          onTimeUpdate={(event) => setCurrentTimeMs(event.currentTarget.currentTime * 1000)}
        />
      ) : (
        <p className="muted">上传伴奏后可同步预览歌词。</p>
      )}
      <div className="lyric-lines" aria-live="polite">
        {lines.length > 0 ? (
          lines.map((line, index) => (
            <p
              className={index === activeIndex ? "lyric-line lyric-line-active" : "lyric-line"}
              data-active={index === activeIndex}
              key={`${line.time_ms}-${index}`}
              ref={index === activeIndex ? activeLine : undefined}
            >
              {line.text || "（间奏）"}
            </p>
          ))
        ) : (
          <p className="muted">请输入带时间标签的逐行 LRC，例如 [00:03.50]第一句歌词。</p>
        )}
      </div>
    </section>
  );
}
