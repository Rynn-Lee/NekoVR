export function SlimeVRIcon({
  drag,
  width = 30,
  height = 30,
}: {
  drag?: boolean;
  width?: number;
  height?: number;
}) {
  return (
    <svg
      width={width}
      height={height}
      viewBox="-4.48 -4.48 24.96 24.96"
      fill="none"
      xmlns="http://www.w3.org/2000/svg"
      data-electron-drag-region={drag}
      className="overflow-visible"
    >
      <g>
        <path
          transform="translate(-4.48, -4.48), scale(1.56)"
          fill="rgb(var(--background-60))"
          d="M9.166.33a2.25 2.25 0 00-2.332 0l-5.25 3.182A2.25 2.25 0 00.5 5.436v5.128a2.25 2.25 0 001.084 1.924l5.25 3.182a2.25 2.25 0 002.332 0l5.25-3.182a2.25 2.25 0 001.084-1.924V5.436a2.25 2.25 0 00-1.084-1.924L9.166.33z"
        />
      </g>
      <g>
        <path
          fillRule="evenodd"
          clipRule="evenodd"
          d="M1 7L4.80061 1.43926C5.56059 0.527292 6.68638 0 7.8735 0H8V4L12 5L15 10L14.1875 11.2188C13.4456 12.3316 12.1967 13 10.8593 13H9L7 16H5L1 7ZM10 9C10.5523 9 11 8.55229 11 8C11 7.44772 10.5523 7 10 7C9.44771 7 9 7.44772 9 8C9 8.55229 9.44771 9 10 9Z"
          fill="rgb(var(--accent-background-30))"
        />
        <path
          d="M10 0.465878V2.43845L12 2.93845V0H11.8735C11.2125 0 10.5704 0.163501 10 0.465878Z"
          fill="rgb(var(--accent-background-30))"
        />
      </g>
    </svg>
  );
}
