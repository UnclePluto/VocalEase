import { cleanup, fireEvent, render, screen } from "@testing-library/react";
import { afterEach, expect, it } from "vitest";

import { activeLyricIndex, LrcPreview, parseLrc } from "./LrcPreview";

afterEach(cleanup);

it("parses and sorts line-level LRC timestamps", () => {
  expect(parseLrc("[00:08.20]第二句\n[00:03.500]第一句\n[ar:歌手]")).toEqual([
    { time_ms: 3500, text: "第一句" },
    { time_ms: 8200, text: "第二句" }
  ]);
});

it("highlights the latest lyric line according to audio currentTime", () => {
  render(
    <LrcPreview
      audioSrc="blob:preview"
      lrc={"[00:01.00]第一句\n[00:05.00]第二句\n[00:10.00]第三句"}
    />
  );
  const audio = screen.getByLabelText("伴奏预览");
  Object.defineProperty(audio, "currentTime", { configurable: true, value: 6 });
  fireEvent.timeUpdate(audio);

  expect(screen.getByText("第二句")).toHaveAttribute("data-active", "true");
  expect(screen.getByText("第一句")).toHaveAttribute("data-active", "false");
  expect(
    activeLyricIndex(
      [
        { time_ms: 1000, text: "第一句" },
        { time_ms: 5000, text: "第二句" }
      ],
      6000
    )
  ).toBe(1);
});
