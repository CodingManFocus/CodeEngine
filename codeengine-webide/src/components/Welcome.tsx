export function Welcome({
  disabled,
  onCreate,
}: {
  disabled: boolean;
  onCreate: () => void;
}) {
  return (
    <section className="welcome">
      <div className="welcome-icon">
        ce<span>.</span>
      </div>
      <span className="eyebrow">A SMALL EDITOR. REAL POSSIBILITIES.</span>
      <h1>아이디어를 서버 위로.</h1>
      <p>
        모듈을 열고, 다음 기능을 만들어 보세요.
        <br />
        Code Engine 문법부터 Java 코드까지 한곳에서.
      </p>
      <button className="primary" disabled={disabled} onClick={onCreate}>
        ＋ 첫 모듈 만들기
      </button>
      <div className="shortcut-grid">
        <span>
          저장<kbd>Ctrl / ⌘ S</kbd>
        </span>
        <span>
          빌드 검사<kbd>Ctrl / ⌘ Enter</kbd>
        </span>
        <span>
          검색·치환<kbd>Ctrl / ⌘ F</kbd>
        </span>
        <span>
          문법 자동 완성<kbd>Ctrl Space</kbd>
        </span>
      </div>
    </section>
  );
}
