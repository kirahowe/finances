export function DragIcon({ className = "", size = 20 }: { className?: string; size?: number }) {
  return (
    <svg
      width={size}
      height={size}
      viewBox="0 0 48 48"
      xmlns="http://www.w3.org/2000/svg"
      className={className}
    >
      <g>
        <path d="M46,20a2,2,0,0,1-2,2H4a2,2,0,0,1-2-2H2a2,2,0,0,1,2-2H44a2,2,0,0,1,2,2Z" fill="currentColor"/>
        <path d="M46,28a2,2,0,0,1-2,2H4a2,2,0,0,1-2-2H2a2,2,0,0,1,2-2H44a2,2,0,0,1,2,2Z" fill="currentColor"/>
      </g>
    </svg>
  );
}
