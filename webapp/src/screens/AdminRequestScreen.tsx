// ================================================================
//  Заявка за юзера → /admin/request (RequireAdmin).
//  Админ оформляет заявку ЗА пользователя по телефону (после звонка
//  «перезвоните мне»): POST /admin/request-for-phone.
//  Бэк сам найдёт/создаст юзера по номеру → заведёт заявку → водители увидят.
// ================================================================
import { useState } from "react";
import { useNavigate } from "react-router-dom";
import { useLang } from "../i18n/lang";
import { ApiError } from "../api/client";
import { adminRequestForPhone } from "../api/admin";
import { SubHeader } from "./ConsentsScreen";
import { IconCheck } from "../components/Icons";
import { AdminIntro, ListedEmpty } from "../components/adminUi";

type State = "idle" | "sending" | "sent" | "error";

export default function AdminRequestScreen() {
  const { appText } = useLang();
  const navigate = useNavigate();

  const [phone, setPhone] = useState("");
  const [name, setName] = useState("");
  const [from, setFrom] = useState("");
  const [to, setTo] = useState("");
  const [seats, setSeats] = useState(1);
  const [when, setWhen] = useState(""); // datetime-local, опц.
  const [comment, setComment] = useState("");

  const [state, setState] = useState<State>("idle");
  const [error, setError] = useState<string | null>(null);
  const [createdId, setCreatedId] = useState<number | null>(null);

  const canSend = phone.trim() && from.trim() && to.trim() && state !== "sending";

  async function submit() {
    if (!canSend) return;
    setState("sending");
    setError(null);
    try {
      const r = await adminRequestForPhone({
        phone: phone.trim(),
        name: name.trim() || undefined,
        from_city: from.trim(),
        to_city: to.trim(),
        seats,
        comment: comment.trim() || undefined,
        // datetime-local → ISO (или пусто = «время уточняется»).
        desired_at: when ? new Date(when).toISOString() : undefined,
      });
      setCreatedId(r.id);
      setState("sent");
    } catch (e) {
      setError(
        e instanceof ApiError && e.message
          ? e.message
          : appText("Не получилось создать заявку. Попробуй ещё раз.", "Заявка булдырып булманы. Ҡабат ҡара.") // DRAFT
      );
      setState("error");
    }
  }

  function reset() {
    setPhone("");
    setName("");
    setFrom("");
    setTo("");
    setSeats(1);
    setWhen("");
    setComment("");
    setCreatedId(null);
    setState("idle");
  }

  if (state === "sent") {
    return (
      <>
        <SubHeader title={appText("Заявка за пользователя", "Ҡулланыусы өсөн заявка")} onBack={() => navigate(-1)} />
        <div className="alist">
          <ListedEmpty
            icon={<IconCheck size={34} />}
            title={appText("Заявка создана для пользователя", "Ҡулланыусы өсөн заявка булдырылды")}
            subtitle={appText(
              `Заявка #${createdId} готова — водители увидят её в ленте.`,
              `Заявка #${createdId} әҙер — йөрөтөүселәр таҫмала күрер.`
            )}
          />
          <button type="button" className="abtn" onClick={reset}>
            {appText("Оформить ещё одну", "Тағы бер рәтләргә")}
          </button>
        </div>
      </>
    );
  }

  return (
    <>
      <SubHeader title={appText("Заявка за пользователя", "Ҡулланыусы өсөн заявка")} onBack={() => navigate(-1)} />
      <div className="alist">
        <AdminIntro>
          {appText(
            "После «Попросить звонок» заполни заявку за человека — водители увидят её как обычную.",
            "«Шылтыратыу һорау»ҙан һуң кеше өсөн заявка тултыр — йөрөтөүселәр уны ғәҙәти күрер."
          )}
        </AdminIntro>

        <label className="field">
          <span className="field__label">{appText("Телефон пользователя", "Ҡулланыусы телефоны")}</span>
          <input
            className="field__input"
            type="tel"
            inputMode="tel"
            value={phone}
            onChange={(e) => setPhone(e.target.value)}
            placeholder="+7 917 000 00 00"
            autoComplete="off"
          />
        </label>

        <label className="field">
          <span className="field__label">{appText("Имя (необязательно)", "Исем (мотлаҡ түгел)")}</span>
          <input
            className="field__input"
            value={name}
            onChange={(e) => setName(e.target.value)}
            placeholder={appText("Как обращаться", "Нисек өндәшергә")}
            autoComplete="off"
          />
        </label>

        <label className="field">
          <span className="field__label">{appText("Откуда", "Ҡайҙан")}</span>
          <input className="field__input" value={from} onChange={(e) => setFrom(e.target.value)} autoComplete="off" />
        </label>
        <label className="field">
          <span className="field__label">{appText("Куда", "Ҡайҙа")}</span>
          <input className="field__input" value={to} onChange={(e) => setTo(e.target.value)} autoComplete="off" />
        </label>

        <label className="field">
          <span className="field__label">{appText("Мест", "Урын")}</span>
          <select
            className="field__input"
            value={seats}
            onChange={(e) => setSeats(parseInt(e.target.value, 10) || 1)}
          >
            {[1, 2, 3, 4, 5, 6, 7, 8].map((n) => (
              <option key={n} value={n}>{n}</option>
            ))}
          </select>
        </label>

        {/* Есть только в вебе: время поездки (в приложении оно уточняется по звонку). */}
        <label className="field">
          <span className="field__label">{appText("Когда (по желанию)", "Ҡасан (теләһәң)")}</span>
          <input
            className="field__input"
            type="datetime-local"
            value={when}
            onChange={(e) => setWhen(e.target.value)}
          />
        </label>

        <label className="field">
          <span className="field__label">{appText("Комментарий", "Аңлатма")}</span>
          <textarea
            className="field__input field__area"
            value={comment}
            onChange={(e) => setComment(e.target.value)}
            rows={2}
            maxLength={2000}
            placeholder={appText("Например: с детским креслом", "Мәҫәлән: бала ултырғысы менән")}
          />
        </label>

        {error && <div className="auth__error">{error}</div>}

        <button type="button" className="abtn abtn--tall" onClick={submit} disabled={!canSend}>
          {state === "sending" ? appText("Создаём…", "Булдырабыҙ…") : appText("Создать заявку", "Заявка булдырыу")}
        </button>
      </div>
    </>
  );
}
