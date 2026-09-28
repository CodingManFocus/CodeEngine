import { useEffect, useRef, type ReactNode } from "react";
export function Dialog({
  title,
  children,
  onClose,
}: {
  title: string;
  children: ReactNode;
  onClose: () => void;
}) {
  const ref = useRef<HTMLDialogElement>(null);
  useEffect(() => {
    const dialog = ref.current!;
    dialog.showModal();
    return () => dialog.close();
  }, []);
  return (
    <dialog
      ref={ref}
      onCancel={(event) => {
        event.preventDefault();
        onClose();
      }}
      aria-label={title}
    >
      <div className="dialog-heading">
        <span className="eyebrow">CODE ENGINE STUDIO</span>
        <button className="icon-button" onClick={onClose} aria-label="닫기">
          ×
        </button>
      </div>
      <h2>{title}</h2>
      {children}
    </dialog>
  );
}
