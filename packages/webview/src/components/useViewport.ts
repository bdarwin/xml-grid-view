import { useEffect, useRef, useState } from "preact/hooks";

/** Tracks scrollTop and clientHeight of a scroll container for virtualization. */
export function useViewport<T extends HTMLElement>() {
  const ref = useRef<T>(null);
  const [state, setState] = useState({ top: 0, height: 0, left: 0, width: 0 });
  useEffect(() => {
    const el = ref.current;
    if (!el) return;
    let frame = 0;
    const update = () => {
      frame = 0;
      setState((s) =>
        s.top === el.scrollTop && s.height === el.clientHeight && s.left === el.scrollLeft && s.width === el.clientWidth
          ? s
          : { top: el.scrollTop, height: el.clientHeight, left: el.scrollLeft, width: el.clientWidth },
      );
    };
    const onScroll = () => {
      if (!frame) frame = requestAnimationFrame(update);
    };
    el.addEventListener("scroll", onScroll, { passive: true });
    const ro = new ResizeObserver(update);
    ro.observe(el);
    update();
    return () => {
      el.removeEventListener("scroll", onScroll);
      ro.disconnect();
      if (frame) cancelAnimationFrame(frame);
    };
  }, []);
  return [ref, state] as const;
}

/** Scrolls a virtual row into view. */
export function scrollRowIntoView(el: HTMLElement | null, index: number, rowHeight: number, headerHeight = 0) {
  if (!el) return;
  const top = index * rowHeight;
  const viewTop = el.scrollTop;
  const viewH = el.clientHeight - headerHeight;
  if (top < viewTop) el.scrollTop = top;
  else if (top + rowHeight > viewTop + viewH) el.scrollTop = top + rowHeight - viewH;
}
