import { templates } from "../language";
interface Props {
  disabled: boolean;
  onClose: () => void;
  onInsert: (code: string) => void;
}
export function LanguageGuide({ disabled, onClose, onInsert }: Props) {
  return (
    <aside className="reference">
      <div className="section-heading">
        <span>LANGUAGE GUIDE</span>
        <button
          className="icon-button"
          aria-label="가이드 닫기"
          onClick={onClose}
        >
          ×
        </button>
      </div>
      <h2>
        작은 문법,
        <br />
        익숙한 Java.
      </h2>
      <p>
        선언은 Code Engine 문법으로,
        <br />
        블록 안은 Java 21로 작성하세요.
      </p>
      {templates.map((item) => (
        <article key={item.label}>
          <div>
            <h3>{item.detail}</h3>
            <button disabled={disabled} onClick={() => onInsert(item.code)}>
              끝에 삽입 ＋
            </button>
          </div>
          <pre>
            {item.code.replace(
              /\$\{([^}]*)\}/g,
              (_, field: string) => field.split(":").at(-1) ?? "",
            )}
          </pre>
        </article>
      ))}
      <p className="hint">
        자동 완성은 문법과 스니펫을 제공합니다. Paper API의 타입·메서드 검사는
        빌드 검사를 사용하세요.
      </p>
    </aside>
  );
}
